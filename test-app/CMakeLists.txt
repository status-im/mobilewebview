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
if(POLICY QTP0004)
    qt_policy(SET QTP0004 NEW)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../mobilewebview
                 ${CMAKE_CURRENT_BINARY_DIR}/mobilewebview)

qt_add_executable(MobileWebViewTest
    main.cpp
)

qt_add_qml_module(MobileWebViewTest
    URI MobileWebViewTest
    VERSION 1.0
    RESOURCE_PREFIX /
    QML_FILES
        qml/main.qml
        qml/HomeScreen.qml
        qml/components/AppButton.qml
        qml/components/AppTextField.qml
        qml/components/FeatureCard.qml
        qml/components/ScreenScaffold.qml
        qml/components/VerdictBadge.qml
        qml/components/WebViewHost.qml
        qml/helpers/ProbeUtils.js
        qml/helpers/Theme.js
        qml/screens/NavigationScreen.qml
        qml/screens/FindScreen.qml
        qml/screens/ZoomScreen.qml
        qml/screens/CookiesScreen.qml
        qml/screens/LocalStorageScreen.qml
        qml/screens/ProfileIsolationScreen.qml
        qml/screens/OriginScreen.qml
        qml/screens/ScriptsScreen.qml
        qml/screens/RenderingScreen.qml
    RESOURCES
        web/test_webchannel.html
        web/storage_profile_test.html
        js/test_script.js
        js/qwebchannel.js
        js/isolation_probe_script.js
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
