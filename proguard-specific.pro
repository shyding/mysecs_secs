# 基本选项
-dontpreverify
-dontwarn
-ignorewarnings
-optimizationpasses 5
-allowaccessmodification
-overloadaggressively
-repackageclasses ''
-flattenpackagehierarchy ''
-mergeinterfacesaggressively

# 保留注解和异常信息
-keepattributes Exceptions,Signature,Deprecated,*Annotation*,EnclosingMethod

# 使用字典
-obfuscationdictionary dictionary.txt
-classobfuscationdictionary dictionary.txt
-packageobfuscationdictionary dictionary.txt

# 使用映射文件
-applymapping mapping.txt
-useuniqueclassmembernames
-dontusemixedcaseclassnames

# 保留所有接口
-keep interface ** { *; }

# 保留所有公共类和方法，除了我们要混淆的类
-keep public class !com.shimizukenta.secs.impl.AbstractSecsMessageWithSource,!com.shimizukenta.secs.secs1.impl.AbstractSecs1CircuitFacade,!com.shimizukenta.secs.secs1ontcpip.ext.multiclient.** { public *; protected *; }

# 保留Secs1OnTcpIpReceiverCommunicatorConfig类不被混淆
-keep class com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig { *; }

# 保留MultiClientSecsServer和ClientConnection类名和公共方法，但混淆内部实现
-keep class com.shimizukenta.secs.secs1ontcpip.ext.multiclient.MultiClientSecsServer {
    public <init>(...);
    public *;
    protected *;
}

-keep class com.shimizukenta.secs.secs1ontcpip.ext.multiclient.ClientConnection {
    public *;
    protected *;
}

# 保留Secs1OnTcpIpMultiClientCommunicator的工厂方法
-keepclassmembers class com.shimizukenta.secs.secs1ontcpip.ext.multiclient.Secs1OnTcpIpMultiClientCommunicator {
    public static ** newInstance(...);
    public static ** open(...);
}

# 保留AbstractSecs1OnTcpIpMultiClientCommunicator的setServer方法
-keepclassmembers class com.shimizukenta.secs.secs1ontcpip.ext.multiclient.AbstractSecs1OnTcpIpMultiClientCommunicator {
    public void setServer(com.shimizukenta.secs.secs1ontcpip.ext.multiclient.MultiClientSecsServer);
}

# 强制混淆这些类
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable
-renamesourcefileattribute SourceFile
