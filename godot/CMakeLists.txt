cmake_minimum_required(VERSION 3.20)
project(rosegold_godot C)

add_library(rosegold_godot SHARED src/rosegold_language.c)
target_include_directories(rosegold_godot PRIVATE include)

if(NOT WIN32)
    target_link_libraries(rosegold_godot PRIVATE dl)
endif()

set_target_properties(rosegold_godot PROPERTIES
    OUTPUT_NAME "rosegold_godot"
    PREFIX ""
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/demo/bin"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/demo/bin")
