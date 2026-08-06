find_package(QT NAMES Qt6 REQUIRED COMPONENTS Core Test WebChannel)
find_package(Qt${QT_VERSION_MAJOR} REQUIRED COMPONENTS Core Test WebChannel)

add_executable(tst_origin_utils
    tst_origin_utils.cpp
    ../src/common/origin_utils.cpp
)
target_include_directories(tst_origin_utils PRIVATE
    ../src/common
)
target_link_libraries(tst_origin_utils PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_origin_utils COMMAND tst_origin_utils)

add_executable(tst_downloadpolicy
    tst_downloadpolicy.cpp
    ../src/common/downloadpolicy.cpp
)
target_include_directories(tst_downloadpolicy PRIVATE
    ../src/common
)
target_link_libraries(tst_downloadpolicy PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_downloadpolicy COMMAND tst_downloadpolicy)

add_executable(tst_inlinedownloadcodec
    tst_inlinedownloadcodec.cpp
    ../src/common/inlinedownloadcodec.cpp
)
target_include_directories(tst_inlinedownloadcodec PRIVATE
    ../src/common
)
target_link_libraries(tst_inlinedownloadcodec PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_inlinedownloadcodec COMMAND tst_inlinedownloadcodec)

add_executable(tst_inlinedownloadwriter
    tst_inlinedownloadwriter.cpp
    ../src/common/inlinedownloadwriter.cpp
)
target_include_directories(tst_inlinedownloadwriter PRIVATE
    ../src/common
)
target_link_libraries(tst_inlinedownloadwriter PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_inlinedownloadwriter COMMAND tst_inlinedownloadwriter)

add_executable(tst_inlinedownloadmessage
    tst_inlinedownloadmessage.cpp
    ../src/common/inlinedownloadmessage.cpp
)
target_include_directories(tst_inlinedownloadmessage PRIVATE
    ../src/common
)
target_link_libraries(tst_inlinedownloadmessage PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_inlinedownloadmessage COMMAND tst_inlinedownloadmessage)

add_executable(tst_freezecontroller
    tst_freezecontroller.cpp
    ../src/common/freezecontroller.cpp
)
target_include_directories(tst_freezecontroller PRIVATE
    ../src/common
)
target_link_libraries(tst_freezecontroller PRIVATE
    Qt::Core
    Qt::Gui
    Qt::Test
)
add_test(NAME tst_freezecontroller COMMAND tst_freezecontroller)

# Platform-independent: MOBILEWEBVIEW_FORCE_DOWNLOAD_TYPES opens the download
# types on hosts without a mobile backend, so Linux CI runs this suite too.
add_executable(tst_downloadregistry
    tst_downloadregistry.cpp
    ../include/MobileWebView/mobilewebviewdownload.h
    ../src/common/downloadregistry.cpp
    ../src/common/downloadpolicy.cpp
    ../src/common/mobilewebviewdownload.cpp
    ../src/common/inlinedownloadcodec.cpp
    ../src/common/inlinedownloadwriter.cpp
)
target_compile_definitions(tst_downloadregistry PRIVATE MOBILEWEBVIEW_FORCE_DOWNLOAD_TYPES)
target_include_directories(tst_downloadregistry PRIVATE
    ../include
    ../src/common
)
target_link_libraries(tst_downloadregistry PRIVATE
    Qt::Core
    Qt::Qml
    Qt::Test
)
add_test(NAME tst_downloadregistry COMMAND tst_downloadregistry)

add_executable(tst_webchanneltransport
    tst_webchanneltransport.cpp
    ../src/common/origin_utils.cpp
    ../src/common/webchanneltransport.cpp
)
target_include_directories(tst_webchanneltransport PRIVATE
    ../src/common
)
target_link_libraries(tst_webchanneltransport PRIVATE
    Qt::Core
    Qt::Test
    Qt::WebChannel
)
add_test(NAME tst_webchanneltransport COMMAND tst_webchanneltransport)

add_executable(tst_userscript_utils
    tst_userscript_utils.cpp
    ../src/common/userscript_utils.cpp
)
target_include_directories(tst_userscript_utils PRIVATE
    ../src/common
)
target_link_libraries(tst_userscript_utils PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_userscript_utils COMMAND tst_userscript_utils)

add_executable(tst_storage_profile_utils
    tst_storage_profile_utils.cpp
    ../src/common/storage_profile_utils.cpp
)
target_include_directories(tst_storage_profile_utils PRIVATE
    ../src/common
)
target_link_libraries(tst_storage_profile_utils PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_storage_profile_utils COMMAND tst_storage_profile_utils)

add_executable(tst_android_js_result
    tst_android_js_result.cpp
    ../src/common/android_js_result.cpp
)
target_include_directories(tst_android_js_result PRIVATE
    ../src/common
)
target_link_libraries(tst_android_js_result PRIVATE
    Qt::Core
    Qt::Test
)
add_test(NAME tst_android_js_result COMMAND tst_android_js_result)

if(APPLE)
    add_executable(tst_userscripts_manager
        tst_userscripts_manager.mm
    )
    target_include_directories(tst_userscripts_manager PRIVATE
        ../include
        ../src
        ../src/common
        ../src/darwin
    )
    target_link_libraries(tst_userscripts_manager PRIVATE
        MobileWebView
        Qt::Core
        Qt::Gui
        Qt::Qml
        Qt::Quick
        Qt::WebChannel
        Qt::Test
        "-framework Foundation"
        "-framework WebKit"
    )
    add_test(NAME tst_userscripts_manager COMMAND tst_userscripts_manager)

    add_executable(tst_mobilewebview_e2e
        tst_mobilewebview_e2e.mm
    )
    target_include_directories(tst_mobilewebview_e2e PRIVATE
        ../include
    )
    target_link_libraries(tst_mobilewebview_e2e PRIVATE
        MobileWebView
        Qt::Core
        Qt::Gui
        Qt::Qml
        Qt::Quick
        Qt::WebChannel
        Qt::Test
    )
    add_test(NAME tst_mobilewebview_e2e COMMAND tst_mobilewebview_e2e)

    add_executable(tst_storage_profiles
        tst_storage_profiles.mm
    )
    target_include_directories(tst_storage_profiles PRIVATE
        ../include
    )
    target_link_libraries(tst_storage_profiles PRIVATE
        MobileWebView
        Qt::Core
        Qt::Gui
        Qt::Qml
        Qt::Quick
        Qt::WebChannel
        Qt::Test
    )
    add_test(NAME tst_storage_profiles COMMAND tst_storage_profiles)

    add_executable(tst_data_clearing
        tst_data_clearing.mm
    )
    target_include_directories(tst_data_clearing PRIVATE
        ../include
        ../src/common
        ../src/darwin
    )
    target_link_libraries(tst_data_clearing PRIVATE
        MobileWebView
        Qt::Core
        Qt::Gui
        Qt::Qml
        Qt::Quick
        Qt::WebChannel
        Qt::Test
        Qt::Network
        "-framework Foundation"
        "-framework WebKit"
    )
    add_test(NAME tst_data_clearing COMMAND tst_data_clearing)

    add_executable(tst_world_isolation
        tst_world_isolation.mm
    )
    target_include_directories(tst_world_isolation PRIVATE
        ../include
    )
    target_link_libraries(tst_world_isolation PRIVATE
        MobileWebView
        Qt::Core
        Qt::Gui
        Qt::Qml
        Qt::Quick
        Qt::WebChannel
        Qt::Test
    )
    add_test(NAME tst_world_isolation COMMAND tst_world_isolation)

    add_executable(tst_mobilewebviewbackend_common
        tst_mobilewebviewbackend_common.mm
        ../include/MobileWebView/mobilewebviewbackend.h
        ../include/MobileWebView/mobilewebviewdownload.h
        ../src/common/mobilewebviewbackend.cpp
        ../src/common/mobilewebviewdownload.cpp
        ../src/common/snapshotimageprovider.cpp
        ../src/common/snapshotitem.cpp
        ../src/common/origin_utils.cpp
        ../src/common/downloadpolicy.cpp
        ../src/common/downloadregistry.cpp
        ../src/common/inlinedownloadcodec.cpp
        ../src/common/inlinedownloadwriter.cpp
        ../src/common/inlinedownloadmessage.cpp
        ../src/common/freezecontroller.cpp
        ../src/common/userscript_utils.cpp
        ../src/common/webchanneltransport.cpp
        ../src/darwin/navigationdelegate.mm
        ../src/darwin/script_utils.mm
        ../src/darwin/origin_utils.mm
        ../src/customwebview.qrc
    )
    target_include_directories(tst_mobilewebviewbackend_common PRIVATE
        ../include
        ../src
        ../src/common
        ../src/darwin
    )
    target_link_libraries(tst_mobilewebviewbackend_common PRIVATE
        Qt::Core
        Qt::Gui
        Qt::Qml
        Qt::Quick
        Qt::WebChannel
        Qt::Test
        "-framework Foundation"
        "-framework WebKit"
    )
    add_test(NAME tst_mobilewebviewbackend_common COMMAND tst_mobilewebviewbackend_common)
endif()
