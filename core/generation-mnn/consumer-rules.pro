# Everything the native library reaches by name.
#
# JNI does not link. It looks symbols up as strings at runtime, so R8 renaming any of the
# names below turns into an UnsatisfiedLinkError the first time a bundle is loaded, or into
# a null jmethodID the first time a step is reported. None of it is reachable from Kotlin in
# a way R8 can see. Consumer rules so they travel with the module: the app should not have to
# know that generating a picture has a native half.

# The five external functions resolve as Java_io_github_..._MnnBridge_nativeLoad and friends,
# so both the class name and the method names have to survive.
-keep class io.github.alpharomercoma.openweights.core.generation.mnn.NativeMnn {
    native <methods>;
}

# `onNativeStep` is the other direction: generation_jni.cpp finds it with GetMethodID on the
# object it was handed, by name and signature, once per denoising step. It is private and
# called from nowhere in Kotlin, which is exactly the shape R8 removes.
-keepclassmembers class io.github.alpharomercoma.openweights.core.generation.mnn.NativeMnn {
    private void onNativeStep(int);
}

# Belt and braces for any other class here that grows a native method later.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
