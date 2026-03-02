cmake_minimum_required(VERSION 3.19)
project(MobileWebView LANGUAGES CXX)

option(MOBILEWEBVIEW_STATIC_LIB "Build MobileWebView as a static library" OFF)
option(MOBILEWEBVIEW_SHADOW_BUILD "Bundle QML resources into the library" ON)

set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(CTest)

find_package(QT NAMES Qt6 REQUIRED COMPONENTS Core)
find_package(Qt${QT_VERSION_MAJOR} COMPONENTS
    Core Qml Gui Quick WebChannel REQUIRED)

# Determine library type
if(MOBILEWEBVIEW_STATIC_LIB)
    set(LIB_TYPE STATIC)
else()
    set(LIB_TYPE SHARED)
endif()

# iOS apps link static libraries into the app bundle. Building this target as a
# shared library on iOS pulls in Qt's iOS platform plugin into libMobileWebView
# and can fail to link with unresolved `_main`.
if(CMAKE_SYSTEM_NAME MATCHES "iOS")
    set(LIB_TYPE STATIC)
endif()

if(MOBILEWEBVIEW_SHADOW_BUILD)
    set(MOBILEWEBVIEW_QRC_COMPILED src/customwebview.qrc)
endif()

set(MOBILEWEBVIEW_COMMON_SOURCES
    include/MobileWebView/mobilewebviewbackend.h
    src/common/mobilewebviewbackend.cpp
    src/common/mobilewebviewbackend_p.h
    src/common/origin_utils.h
    src/common/origin_utils.cpp
    src/common/userscript_utils.h
    src/common/userscript_utils.cpp
    src/common/webchanneltransport.h
    src/common/webchanneltransport.cpp
)

set(MOBILEWEBVIEW_DARWIN_SOURCES
    src/darwin/mobilewebviewbackend_darwin.mm
    src/darwin/navigationdelegate.h
    src/darwin/navigationdelegate.mm
    src/darwin/userscripts.h
    src/darwin/userscripts.mm
    src/darwin/origin_utils.h
    src/darwin/origin_utils.mm
    src/darwin/script_utils.h
    src/darwin/script_utils.mm
    src/darwin/dispatch_utils.h
)

set(MOBILEWEBVIEW_PLATFORM_SOURCES "")
set(MOBILEWEBVIEW_PLATFORM_LIBS "")

if(CMAKE_SYSTEM_NAME MATCHES "Darwin")
    find_library(Foundation Foundation REQUIRED)
    find_library(WebKit WebKit REQUIRED)
    list(APPEND MOBILEWEBVIEW_PLATFORM_SOURCES ${MOBILEWEBVIEW_DARWIN_SOURCES})
    list(APPEND MOBILEWEBVIEW_PLATFORM_LIBS ${Foundation} ${WebKit})

elseif(CMAKE_SYSTEM_NAME MATCHES "iOS")
    list(APPEND MOBILEWEBVIEW_PLATFORM_SOURCES ${MOBILEWEBVIEW_DARWIN_SOURCES})
    # Link by framework name (not absolute SDK path) so switching between
    # iphoneos and iphonesimulator does not retain stale framework locations.
    list(APPEND MOBILEWEBVIEW_PLATFORM_LIBS
        "-framework UIKit"
        "-framework Foundation"
        "-framework WebKit"
    )

elseif(CMAKE_SYSTEM_NAME MATCHES "Android")
    list(APPEND MOBILEWEBVIEW_PLATFORM_SOURCES
        src/android/mobilewebviewbackend_android.cpp
    )
endif()

if(CMAKE_SYSTEM_NAME MATCHES "Darwin|iOS|Android")
    add_library(MobileWebView ${LIB_TYPE}
        ${MOBILEWEBVIEW_QRC_COMPILED}
        ${MOBILEWEBVIEW_COMMON_SOURCES}
        ${MOBILEWEBVIEW_PLATFORM_SOURCES}
    )

    if(CMAKE_SYSTEM_NAME MATCHES "iOS")
        # Only the final app target needs signing on iOS.
        set_target_properties(MobileWebView PROPERTIES
            XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED NO
            XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED NO
        )
    endif()

    target_compile_features(MobileWebView PRIVATE cxx_std_17)

    target_link_libraries(MobileWebView PUBLIC
        Qt::Core
        Qt::Qml
        Qt::Gui
        Qt::Quick
        Qt::WebChannel
    )

    if(MOBILEWEBVIEW_PLATFORM_LIBS)
        target_link_libraries(MobileWebView PRIVATE ${MOBILEWEBVIEW_PLATFORM_LIBS})
    endif()

    target_include_directories(MobileWebView PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    )

    target_include_directories(MobileWebView PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
    )

    if(CMAKE_SYSTEM_NAME MATCHES "Android")
        set(MOBILEWEBVIEW_ANDROID_JAVA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/android"
            CACHE PATH "Path to MobileWebView Android sources")
        if(NOT PROJECT_IS_TOP_LEVEL)
            set(MOBILEWEBVIEW_ANDROID_JAVA_DIR "${MOBILEWEBVIEW_ANDROID_JAVA_DIR}" PARENT_SCOPE)
        endif()
    endif()

    install(TARGETS MobileWebView
        RUNTIME DESTINATION bin
        LIBRARY DESTINATION lib
        ARCHIVE DESTINATION lib
    )
    install(DIRECTORY include/MobileWebView DESTINATION include)

else()
    add_library(MobileWebView INTERFACE)
    message(NOTICE "MobileWebView: Unsupported platform (${CMAKE_SYSTEM_NAME}), creating empty interface target")
endif()

if(BUILD_TESTING)
    add_subdirectory(tests)
endif()
