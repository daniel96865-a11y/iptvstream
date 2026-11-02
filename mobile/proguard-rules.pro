-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Cast: CastOptionsProvider wird nur per Manifest-Metadaten (Reflection) geladen.
-keep class de.dgstudios.iptvstream.CastOptionsProvider { <init>(); *; }
