cmake_minimum_required(VERSION 3.21)

project(MobileWebViewTest VERSION 1.0.0 LANGUAGES CXX)

if(APPLE)
    enable_language(OBJCXX)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

find_package(QT NAMES Qt6 REQUIRED COMPONENTS Core)
find_package(Qt${QT_VERSION_MAJOR} REQUIRED COMPONENTS Core Gui Qml Quick WebChannel)

if(POLICY QTP0002)
    qt_policy(SET QTP0002 NEW)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../mobilewebview
                 ${CMAKE_CURRENT_BINARY_DIR}/mobilewebview)

qt_add_executable(MobileWebViewTest
    main.cpp
)

qt_add_resources(MobileWebViewTest "app_resources"
    PREFIX "/"
    BASE "${CMAKE_CURRENT_SOURCE_DIR}"
    FILES
        qml/main.qml
        qml/TestWidget.qml
        qml/TopBar.qml
        web/test_webchannel.html
        js/test_script.js
        js/qwebchannel.js
)

target_link_libraries(MobileWebViewTest PRIVATE
    MobileWebView
    Qt::Core
    Qt::Gui
    Qt::Qml
    Qt::Quick
    Qt::WebChannel
)

target_include_directories(MobileWebViewTest PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../mobilewebview/include
)

set_target_properties(MobileWebViewTest PROPERTIES
    MACOSX_BUNDLE TRUE
    WIN32_EXECUTABLE TRUE
)

if(CMAKE_SYSTEM_NAME MATCHES "iOS")
    set_target_properties(MobileWebViewTest PROPERTIES
        XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED YES
        XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED YES
        XCODE_ATTRIBUTE_CODE_SIGN_STYLE Automatic
    )
    if(DEFINED CMAKE_XCODE_ATTRIBUTE_DEVELOPMENT_TEAM)
        set_target_properties(MobileWebViewTest PROPERTIES
            XCODE_ATTRIBUTE_DEVELOPMENT_TEAM "${CMAKE_XCODE_ATTRIBUTE_DEVELOPMENT_TEAM}"
        )
    endif()
endif()

if(ANDROID)
    set_property(TARGET MobileWebViewTest APPEND PROPERTY
        QT_ANDROID_PACKAGE_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../mobilewebview/android"
    )
endif()

qt_finalize_executable(MobileWebViewTest)
