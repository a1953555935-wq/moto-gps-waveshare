#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <variant>

#include "moto/ble_protocol/ble_protocol.hpp"

namespace {
struct Codec {
  explicit Codec(std::size_t maximum) : max_frame_size(maximum) {}
  std::size_t max_frame_size;
  moto::ble::SequenceGenerator sequence;
  moto::ble::Reassembler inbound;
};

void fail(JNIEnv* env, const char* message) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type != nullptr) env->ThrowNew(type, message);
}

moto::ble::Bytes read_bytes(JNIEnv* env, jbyteArray array) {
  const auto size = env->GetArrayLength(array);
  moto::ble::Bytes bytes(static_cast<std::size_t>(size));
  if (size > 0) {
    env->GetByteArrayRegion(array, 0, size,
                            reinterpret_cast<jbyte*>(bytes.data()));
  }
  return bytes;
}

jobjectArray encode(JNIEnv* env, Codec* codec, const moto::ble::Message& message) {
  if (codec == nullptr) { fail(env, "BLE codec is closed"); return nullptr; }
  auto payload = moto::ble::encode_message(message);
  if (!payload.ok()) { fail(env, moto::ble::to_string(payload.error)); return nullptr; }
  auto frames = moto::ble::fragment_message(
      moto::ble::message_type(message), codec->sequence.next(),
      moto::ble::ByteView(payload.value), codec->max_frame_size);
  if (!frames.ok()) { fail(env, moto::ble::to_string(frames.error)); return nullptr; }
  jclass byte_array_type = env->FindClass("[B");
  if (byte_array_type == nullptr) return nullptr;
  auto result = env->NewObjectArray(static_cast<jsize>(frames.value.size()),
                                    byte_array_type, nullptr);
  if (result == nullptr) return nullptr;
  for (std::size_t i = 0; i < frames.value.size(); ++i) {
    const auto& frame = frames.value[i];
    auto bytes = env->NewByteArray(static_cast<jsize>(frame.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(frame.size()),
                            reinterpret_cast<const jbyte*>(frame.data()));
    env->SetObjectArrayElement(result, static_cast<jsize>(i), bytes);
    env->DeleteLocalRef(bytes);
  }
  return result;
}

jintArray int_result(JNIEnv* env, std::initializer_list<jint> values) {
  auto result = env->NewIntArray(static_cast<jsize>(values.size()));
  if (result != nullptr) env->SetIntArrayRegion(result, 0,
      static_cast<jsize>(values.size()), values.begin());
  return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_motogps_android_ble_NativeBleCodec_create(JNIEnv* env, jobject, jint maximum) {
  if (maximum < 13 || maximum > 512) {
    fail(env, "Invalid BLE maximum frame size");
    return 0;
  }
  return reinterpret_cast<jlong>(new Codec(static_cast<std::size_t>(maximum)));
}

extern "C" JNIEXPORT void JNICALL
Java_org_motogps_android_ble_NativeBleCodec_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<Codec*>(handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_motogps_android_ble_NativeBleCodec_connection(
    JNIEnv* env, jobject, jlong handle, jint session, jint state) {
  if (session == 0 || (state != 0 && state != 1)) {
    fail(env, "Invalid handshake session or state");
    return nullptr;
  }
  auto* codec = reinterpret_cast<Codec*>(handle);
  moto::ble::ConnectionStatus status;
  status.state = static_cast<moto::ble::ConnectionState>(state);
  status.capabilities = moto::ble::CapabilityNavigation |
      moto::ble::CapabilityRouteGeometry | moto::ble::CapabilityTraffic |
      moto::ble::CapabilityTouchCommands | moto::ble::CapabilityCommandAck;
  status.session_id = static_cast<std::uint32_t>(session);
  status.max_frame_size = static_cast<std::uint16_t>(codec->max_frame_size);
  return encode(env, codec, moto::ble::Message{status});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_motogps_android_ble_NativeBleCodec_heartbeat(
    JNIEnv* env, jobject, jlong handle, jint session, jint elapsed) {
  moto::ble::Heartbeat heartbeat;
  heartbeat.session_id = static_cast<std::uint32_t>(session);
  heartbeat.monotonic_ms = static_cast<std::uint32_t>(elapsed);
  return encode(env, reinterpret_cast<Codec*>(handle), moto::ble::Message{heartbeat});
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_motogps_android_ble_NativeBleCodec_receive(
    JNIEnv* env, jobject, jlong handle, jbyteArray frame, jlong now_ms) {
  auto* codec = reinterpret_cast<Codec*>(handle);
  if (codec == nullptr || frame == nullptr) {
    fail(env, "BLE codec is closed or frame is null");
    return nullptr;
  }
  auto bytes = read_bytes(env, frame);
  auto assembled = codec->inbound.push(
      moto::ble::ByteView(bytes), static_cast<moto::ble::TimestampMs>(now_ms));
  if (assembled.state == moto::ble::ReassemblyState::Error) {
    fail(env, moto::ble::to_string(assembled.error));
    return nullptr;
  }
  if (!assembled.complete()) return nullptr;
  auto decoded = moto::ble::decode_message(assembled.message.type,
      moto::ble::ByteView(assembled.message.payload));
  if (!decoded.ok()) { fail(env, moto::ble::to_string(decoded.error)); return nullptr; }
  if (const auto* status = std::get_if<moto::ble::ConnectionStatus>(&decoded.value)) {
    return int_result(env, {1, static_cast<jint>(status->role),
        static_cast<jint>(status->state), status->minimum_version,
        status->maximum_version, static_cast<jint>(status->capabilities),
        static_cast<jint>(status->session_id), status->max_frame_size,
        status->heartbeat_interval_ms});
  }
  if (const auto* heartbeat = std::get_if<moto::ble::Heartbeat>(&decoded.value)) {
    return int_result(env, {2, static_cast<jint>(heartbeat->session_id)});
  }
  if (const auto* ack = std::get_if<moto::ble::Ack>(&decoded.value)) {
    return int_result(env, {3, ack->acknowledged_sequence,
        static_cast<jint>(ack->status), ack->command_id});
  }
  return int_result(env, {static_cast<jint>(assembled.message.type)});
}
