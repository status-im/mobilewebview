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

    add_executable(tst_mobilewebviewbackend_common
        tst_mobilewebviewbackend_common.mm
        ../include/MobileWebView/mobilewebviewbackend.h
        ../src/common/mobilewebviewbackend.cpp
        ../src/common/snapshotimageprovider.cpp
        ../src/common/snapshotitem.cpp
        ../src/common/origin_utils.cpp
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
