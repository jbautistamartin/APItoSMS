# ─────────────────────────────────────────────────────────────────────────────
# Reglas de ProGuard / R8 específicas de APItoSMS
#
# Hilt, Room, Navigation, WorkManager y kotlinx.serialization distribuyen sus
# propias reglas de consumidor dentro de sus artefactos, así que no hay que
# repetirlas aquí. Solo queda lo que R8 no puede deducir por sí mismo.
#
# Desde AGP 9, R8 aplica el modo completo estricto: un «-keep class X» ya no
# conserva el constructor sin argumentos. Las clases que se instancian por
# reflexión lo piden de forma explícita con { <init>(); }.
# ─────────────────────────────────────────────────────────────────────────────

# Las anotaciones se conservan: Room y Hilt las leen en tiempo de compilación,
# pero kotlinx.serialization y la reflexión de Kotlin las necesitan en runtime.
-keepattributes *Annotation*

# Metadatos de Kotlin: sin ellos, los data class serializables pierden la
# información de sus propiedades.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Room: la implementación generada de la base de datos y las entidades.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Los enum que se persisten como texto (EstadoMensaje, Ambito, LogTipo) se
# reconstruyen con valueOf(), que es reflexión: sin esto R8 puede renombrar sus
# constantes y la lectura de la base de datos fallaría en release.
-keepclassmembers enum com.capicua.apitosms.domain.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Fragments instanciados por nombre desde el grafo de navegación.
-keep class com.capicua.apitosms.presentation.** extends androidx.fragment.app.Fragment { <init>(); }

# El JSON de la API (domain.api) y el de la copia de ajustes son @Serializable: el plugin genera
# los serializadores y kotlinx.serialization trae sus reglas, así que no hace falta nada más.
