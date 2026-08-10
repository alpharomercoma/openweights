# Everything the native library reaches by name.
#
# JNI does not link. It looks symbols up as strings at runtime, so R8 renaming any of the
# names below turns into an UnsatisfiedLinkError or a null jmethodID the first time a model
# is loaded. None of it is reachable from Kotlin in a way R8 can see, which is why it has
# to be spelled out. These rules are consumer rules so they travel with the module: the app
# should not have to know that the engine has a native half.

# The 14 external functions resolve as Java_io_github_..._LlamaBridge_nativeLoadModel and
# friends, so both the class name and the method names have to survive.
-keep class io.github.alpharomercoma.openweights.core.engine.LlamaBridge {
    native <methods>;
}

# Belt and braces for any other class that grows a native method later.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# llama_jni.cpp does FindClass on this to throw a load or generation failure back into
# Kotlin, then ThrowNew, which needs the String constructor. Nothing else about the class
# is reached by name, so nothing else is kept.
-keep class io.github.alpharomercoma.openweights.core.engine.LlamaException {
    <init>(java.lang.String);
}

# The token and reply callbacks are found with GetMethodID while generation is running.
#
# These rules said `-keep,allowobfuscation` and that shipped a release build which aborted
# the process on the first token of every reply:
#
#   No pending exception expected: java.lang.NoSuchMethodError:
#   no non-static method "Lw2;.onToken(Ljava/lang/String;)Z"
#
# `allowobfuscation` does not mean "the class may be renamed". It means the whole match may
# be renamed, members included, and the member name is the only part JNI actually needs:
# native code gets the class with GetObjectClass, so it never spells the class out, and then
# asks for "onToken" as a string. Granting permission to rename that method granted
# permission to break every generation, in a way no debug build can show, because R8 does
# not run there.
-keep interface io.github.alpharomercoma.openweights.core.engine.LlamaBridge$TokenSink {
    boolean onToken(java.lang.String);
}
-keep interface io.github.alpharomercoma.openweights.core.engine.LlamaBridge$ReplySink {
    void onReply(java.lang.String, java.lang.String, java.lang.String[]);
}
-keepclassmembers class * implements
    io.github.alpharomercoma.openweights.core.engine.LlamaBridge$TokenSink {
    boolean onToken(java.lang.String);
}
-keepclassmembers class * implements
    io.github.alpharomercoma.openweights.core.engine.LlamaBridge$ReplySink {
    void onReply(java.lang.String, java.lang.String, java.lang.String[]);
}
