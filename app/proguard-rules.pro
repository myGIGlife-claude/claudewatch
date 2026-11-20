# JSch instantiates ciphers, KEX, MACs and signature classes by name from config strings
# (Class.forName), so R8 cannot see they are used. Keep the whole library.
-keep class com.jcraft.jsch.** { *; }
# Optional JSch integrations (JNA, Bouncy Castle, log4j/slf4j, newer JDK APIs) are not shipped.
-dontwarn com.jcraft.jsch.**
-dontwarn com.sun.jna.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**
