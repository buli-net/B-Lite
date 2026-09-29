-dontskipnonpubliclibraryclasses
-dontoptimize
-dontpreverify
-dontobfuscate
-verbose

-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

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

-dontwarn module-info
-dontwarn java.lang.invoke.**

# Android Support Library 28
-dontnote android.widget.SearchView


# bitcoinj
-keep,includedescriptorclasses class org.bitcoinj.wallet.Protos$** { *; }
-keepclassmembers class org.bitcoinj.wallet.Protos { com.google.protobuf.Descriptors$FileDescriptor descriptor; }
-keep,includedescriptorclasses class org.bitcoin.protocols.payments.Protos$** { *; }
-keepclassmembers class org.bitcoin.protocols.payments.Protos { com.google.protobuf.Descriptors$FileDescriptor descriptor; }
-dontwarn org.bitcoinj.store.LevelDBBlockStore
-dontnote org.bitcoinj.crypto.DRMWorkaround
-dontnote org.bitcoinj.crypto.TrustStoreLoader$DefaultTrustStoreLoader
-dontwarn org.bitcoinj.store.LevelDBFullPrunedBlockStore**


# Bouncy Castle

-dontwarn javax.naming.**

# protobuf-java
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

# Guava

-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn com.google.errorprone.annotations.**

-dontnote com.google.common.reflect.**
-dontnote com.google.common.util.concurrent.MoreExecutors

-dontnote com.google.common.hash.Striped64
-dontnote com.google.common.hash.Striped64$Cell

-dontnote com.google.common.cache.Striped64
-dontnote com.google.common.cache.Striped64$Cell

-dontnote com.google.common.util.concurrent.AbstractFuture$UnsafeAtomicHelper

-dontnote com.google.common.io.TempFileCreator
-dontnote com.google.common.io.TempFileCreator$JavaNioCreator
