# ============================================================
# Android / Java
# ============================================================

-keepattributes *Annotation*

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers,includedescriptorclasses public class * extends android.view.View {
    void set*(***);
    *** get*();
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class **.R$* {
    public static <fields>;
}


# ============================================================
# Android Support Library 28
# ============================================================

-dontnote android.widget.SearchView


# ============================================================
# bitcoinj 0.17.1 - protobuf wallet
#
# IMPORTANT:
# The generated wallet protobuf classes are under
# org.bitcoinj.protobuf.wallet.Protos
# NOT org.bitcoinj.wallet.Protos
# ============================================================

-keep class org.bitcoinj.protobuf.wallet.Protos { *; }
-keep class org.bitcoinj.protobuf.wallet.Protos$* { *; }


# ============================================================
# bitcoinj - payment protocol protobuf
# ============================================================

-keep class org.bitcoin.protocols.payments.Protos { *; }
-keep class org.bitcoin.protocols.payments.Protos$* { *; }


# ============================================================
# bitcoinj optional / platform-dependent classes
# ============================================================

-dontwarn org.bitcoinj.store.LevelDBBlockStore
-dontwarn org.bitcoinj.store.LevelDBFullPrunedBlockStore**
-dontwarn org.bitcoinj.crypto.DRMWorkaround
-dontwarn javax.naming.**


# ============================================================
# Java platform / Guava
# ============================================================

-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn java.lang.invoke.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn module-info


# ============================================================
# protobuf-javalite
#
# Keep warnings/notes suppressed only.
# Generated bitcoinj protobuf classes themselves are kept above.
# ============================================================

-dontnote com.google.protobuf.Android
-dontnote com.google.protobuf.ExtensionRegistryFactory
-dontnote com.google.protobuf.ExtensionRegistryLite$ExtensionClassHolder
-dontnote com.google.protobuf.ExtensionSchemas
-dontnote com.google.protobuf.FieldInfo
-dontnote com.google.protobuf.FieldType
-dontnote com.google.protobuf.ByteBufferWriter
-dontnote com.google.protobuf.Descriptors$FileDescriptor
-dontnote com.google.protobuf.GeneratedMessageLite$SerializedForm
-dontnote com.google.protobuf.ListFieldSchemas
-dontnote com.google.protobuf.ManifestSchemaFactory
-dontnote com.google.protobuf.MapFieldSchemas
-dontnote com.google.protobuf.MessageSchema
-dontnote com.google.protobuf.NewInstanceSchemas
-dontnote com.google.protobuf.SchemaUtil
-dontnote com.google.protobuf.UnsafeUtil


# ============================================================
# Guava
# ============================================================

-dontnote com.google.common.reflect.**
-dontnote com.google.common.util.concurrent.MoreExecutors
-dontnote com.google.common.hash.Striped64
-dontnote com.google.common.hash.Striped64$Cell
-dontnote com.google.common.cache.Striped64
-dontnote com.google.common.cache.Striped64$Cell
-dontnote com.google.common.util.concurrent.AbstractFuture$UnsafeAtomicHelper
-dontnote com.google.common.io.TempFileCreator
-dontnote com.google.common.io.TempFileCreator$JavaNioCreator
