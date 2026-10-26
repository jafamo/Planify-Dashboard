# Retrofit y Gson necesitan firmas genéricas, anotaciones y los modelos intactos.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.lito.planify.dashboard.data.** { *; }
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-dontwarn sun.misc.**
# biweekly: Jackson (jCal) se excluye en build.gradle.kts. Sus clases, sin renombrar: carga sus .properties al lado de
# la clase (Biweekly.class.getResourceAsStream("biweekly.properties")) y, renombrada fuera de su paquete, no los encuentra.
-dontwarn com.fasterxml.jackson.**
-keep class biweekly.** { *; }
