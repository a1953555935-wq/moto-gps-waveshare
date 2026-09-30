cmake_minimum_required(VERSION 3.22.1)
project(moto_android_ble LANGUAGES CXX)

set(BLE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../../shared/ble_protocol")
add_library(moto_android_ble SHARED
    native_ble_codec.cpp
    "${BLE_ROOT}/src/ble_protocol.cpp")
target_include_directories(moto_android_ble PRIVATE "${BLE_ROOT}/include")
target_compile_features(moto_android_ble PRIVATE cxx_std_17)
