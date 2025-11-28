/* SWIG interface file for WaterUI Android bindings */
% module(directors = "1") NativeBindings

    % {
#include "waterui.h"
#include <android/log.h>
#include <dlfcn.h>
#include <string>

#define LOG_TAG "WaterUI.SWIG"

  // Global symbol table - loaded dynamically from the Rust library
  static void *g_rust_lib = nullptr;
  static bool g_symbols_ready = false;

// Function pointer type definitions for all FFI functions
#define DEFINE_FN_PTR(name, ret, ...) typedef ret (*name##_fn)(__VA_ARGS__);

  // Include all function pointer definitions here
  // These will be populated when the Rust library is loaded

  // Helper to load a symbol from the Rust library
  template <typename T> static T load_symbol(const char *name) {
    if (!g_rust_lib)
      return nullptr;
    return reinterpret_cast<T>(dlsym(g_rust_lib, name));
  }

  // Initialize by loading symbols from the Rust library
  static bool init_waterui_symbols(const char *lib_path) {
    if (g_symbols_ready)
      return true;

    g_rust_lib = dlopen(lib_path, RTLD_NOW | RTLD_GLOBAL);
    if (!g_rust_lib) {
      __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                          "Failed to load Rust library: %s", dlerror());
      return false;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "Loaded WaterUI symbols from %s", lib_path);
    g_symbols_ready = true;
    return true;
  }
  %
}

/* Java package configuration */
% pragma(java) jniclasscode =
    % {static {try {// First load the Rust library containing WaterUI FFI
                    String[] possibleLibs = {"water_demo", "waterui"};
boolean loaded = false;
for (String lib : possibleLibs) {
  try {
    System.loadLibrary(lib);
    loaded = true;
    break;
  } catch (UnsatisfiedLinkError e) {
    // Try next
  }
}
if (!loaded) {
  throw new UnsatisfiedLinkError("Could not load WaterUI Rust library");
}

// Then load our SWIG wrapper
System.loadLibrary("waterui_android");
}
catch (UnsatisfiedLinkError e) {
  System.err.println("Failed to load native libraries: " + e);
  throw e;
}
}
%
}

/* Type mappings for basic types */
% include<stdint.i> %
    include<typemaps.i>

    /* Handle opaque pointers */
    % define OPAQUE_PTR(TYPE) % typemap(jni) TYPE * "jlong" %
    typemap(jtype) TYPE * "long" % typemap(jstype) TYPE * "long" %
    typemap(javain) TYPE * "$javainput" % typemap(javaout) TYPE * {
  return $jnicall;
}
% typemap(in) TYPE * % {
  $1 = (TYPE *)$input;
  %
}
% typemap(out) TYPE * % {
  $result = (jlong)$1;
  %
}
%
    enddef

    /* Apply opaque pointer handling to all WaterUI types */
    OPAQUE_PTR(WuiEnv) OPAQUE_PTR(WuiAnyView) OPAQUE_PTR(WuiWatcherGuard)
        OPAQUE_PTR(WuiWatcherMetadata)

    /* Include the header to parse all declarations */
    % include "waterui.h"

    /* Additional helper functions */
    % inline % {
  // Initialize symbols from a specific library path
  bool waterui_init_from_lib(const char *lib_path) {
    return init_waterui_symbols(lib_path);
  }
  %
}
