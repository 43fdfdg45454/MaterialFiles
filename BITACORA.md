# Bitácora — soporte NFS para Material Files

Registro de lo que se hizo el 26 de septiembre de 2026 (y la madrugada del 27) para llegar al
estado actual. Los lineamientos vigentes están en [CLAUDE.md](CLAUDE.md).

## 1. Evaluación y plan

- Se evaluó cuánto costaba agregar NFS como tipo de almacenamiento, junto a FTP, SFTP, SMB y
  WebDAV. Conclusión: viable siguiendo el modelo del proveedor SFTP, con libnfs como biblioteca
  cliente.
- Se descartó migrar los proveedores existentes a C++: mucho costo y ningún beneficio para este
  objetivo.
- Se armó un plan (documento aparte) y quedó aprobado con dos condiciones: publicar el puente como
  AAR y usarlo desde el fork, y **no ejecutar nada en la computadora del usuario**, trabajando solo
  en la nube y en GitHub.

## 2. libnfs-android (el AAR)

- Repositorio nuevo con libnfs 8.0.0 como submódulo, puente JNI en C y la clase `Nfs` en Java.
  Se publica como repositorio Maven en la rama `maven`, porque la CI no puede crear tags.
- CI con pruebas de verdad contra un servidor nfsd de Linux, no solo compilación.
- Primeros arreglos, como parches sobre libnfs sin tocar el submódulo: FSID mal alineado, códigos
  de error de timeout y de RPC, y un error de NFSv3 que convertía ENOENT en ERANGE.

## 3. Proveedor NFS en Material Files

- `NfsFileSystemProvider`, rutas, atributos, pool de conexiones, pantalla para agregar y editar
  servidores (host, puerto, ruta del export, UID/GID, grupos adicionales, solo lectura).
- Pruebas instrumentadas en un emulador contra nfsd en la CI.
- Arreglos en el camino: excepciones de solo lectura, comparación de atributos, `ArchiveException`
  al comprimir dentro del NFS (buffers directos leídos con `array()`).
- Build **Release** con id propio (`me.zhanghai.android.files.nfs`), manteniendo el debug. APK con
  nombre claro: `MaterialFiles-NFS-<versión>.apk`. Se generó una clave de firma privada, guardada fuera del
  repositorio; como la API de secrets no era accesible desde acá, mientras tanto se firma
  con una clave provisoria pública.

## 4. Rendimiento

- Queja inicial: 28 MB tardaban ~30 s por Wi-Fi. Causa: 8 KB por ida y vuelta.
- Escrituras agrupadas y paralelas (hasta 16 RPC de 1 MiB), lecturas por ventanas con lectura
  anticipada. En el servidor de pruebas se pasó de ~1,3 MB/s a 127–150 MB/s; en el emulador, el
  límite pasó a ser la red del propio emulador.
- Tamaños adaptativos para que cada transferencia tarde ~1 s: en enlaces lentos (VPN por datos
  móviles) un lote nunca se acerca al timeout.
- Se optimizó el tiempo de la CI (sin esperas fijas, caché del AVD, jobs en paralelo).

## 5. Solo NFSv4.2, VPN y cambios de red

- A pedido del usuario se eliminó todo el soporte de NFSv3.
- Copia en el servidor (COPY y CLONE de NFSv4.2): en un sistema de archivos con reflinks, 8 MiB en
  menos de 1 ms.
- Red VPN simulada en la CI: namespace de red con 40 ms de RTT, jitter, 0,3 % de pérdida y
  MTU 1420.
- Reconexión automática que vuelve a enlazar la sesión NFSv4.2; `NetworkMonitor` en la app detecta
  cambios de red.
- Exactamente una vez: se hizo que el servidor guarde las respuestas de las operaciones que
  modifican (`sa_cachethis`), para que una operación reenviada tras una reconexión no se ejecute
  dos veces.
- Depuración de la prueba de resiliencia. El fallo de "RENAME reenviado" no era un número de
  secuencia reutilizado: la LOOKUP previa, sin respuesta guardada, recibía `RETRY_UNCACHED_REP` y
  el RENAME nunca llegaba a ejecutarse. Solución: libnfs reenvía sola esas operaciones idempotentes
  con un número de secuencia nuevo. Además se corrigió un error por el que el último fragmento
  fallido de una lectura no se reintentaba.
- La prueba de VPN en el emulador tardaba demasiado: se ajustó el tamaño de los benchmarks por
  perfil de red.

## 6. Preguntas de configuración del servidor

- Filtrar exports por nombre de host o por IP, con opciones distintas según la red por la que
  llega el cliente. Con una VPN, el enmascaramiento (MASQUERADE) hace que todos los clientes
  parezcan el propio servidor.
- Cómo funcionan UID y GID con AUTH_SYS, `root_squash` y `all_squash`.
- Qué mecanismos de autenticación ofrece NFSv4 (Kerberos, RPC-with-TLS). Se eligió mTLS.

## 7. TLS y mTLS

- RPC-with-TLS (RFC 9289) con un hook de conexión en libnfs y un relay en Java: socketpair,
  sondeo STARTTLS, TLS 1.3 y ALPN `sunrpc`. Así funciona igual en Android y en la CI, sin depender
  de kTLS en el celular.
- CI con `tlshd` y exports `tls` y `mtls`. Hallazgos: nfsd solo aplica la política de un export
  anidado si es un punto de montaje propio; la versión 0.9 de `tlshd` ignora `x509.truststore`.
- En la app: "Seguridad de la conexión" (Ninguna, TLS, mTLS), separada de la identidad UID/GID.
  CA del sistema y del usuario; certificado de cliente desde el almacén de claves de Android;
  errores de TLS con su causa; compatibilidad con los servidores ya guardados.
- Rendimiento con TLS: la escritura caía de ~350 a ~60 MB/s. Se descartó que fuera el búfer de
  recepción del servidor midiendo en la CI. La causa real: el relay fijaba el búfer de su socket
  TCP y Linux lo recortaba a ~200 KiB sin autoajuste. Sin fijarlo, TLS y mTLS rinden igual que sin
  TLS (~310–340 MB/s en el enlace tipo Wi-Fi).
- Se quitó del formulario la nota sobre "NFS no cifra", que ya no aplicaba.

## 8. Errores encontrados en uso real

- **Fechas absurdas en los archivos nuevos** (30 años atrás, un año adelante). Las creaciones con
  `O_EXCL` usaban `EXCLUSIVE4`, y el servidor guarda el verificador aleatorio en las fechas hasta
  que el cliente las fija, cosa que libnfs nunca hacía. Ahora se usa `GUARDED4`. Hay pruebas de
  fechas y permisos de los archivos recién creados.
- **"Guardar" en archivos dentro de un ZIP**: se editaba una copia temporal. Era un comportamiento
  heredado de la app original. Ahora el editor los abre en solo lectura y lo avisa.
- Emulador con perfil VPN: con el MTU de 64 KiB de loopback las subidas se trababan; con MTU 1420
  (como WireGuard) la prueba bajó de 13–16 a 6 minutos.

## 9. Configuración del servidor: lo aprendido

- Exports con `all_squash`: el grupo de los archivos nuevos viene del bit setgid de la carpeta.
  Un `NFS4ERR_ACCESS` puede venir de la pseudo-raíz de NFSv4: las carpetas de arriba del export se
  atraviesan con el UID/GID del cliente y necesitan `o+x`.
- `all_squash` también tiene sentido en exports de solo lectura: define qué se puede leer.
- mTLS en el servidor: `tlshd` (configuración en `/etc/tlshd/config` desde la versión 1.3),
  permisos 644/600 de los certificados, `xprtsec=mtls` en el export.
- Certificados: script de certificados de cliente (`clientAuth`, `.p12` con `-legacy`); el SAN
  tiene que coincidir exacto con el nombre usado en la app; instalar un `.p12` que trae la CA
  también la instala como CA de confianza en Android; por qué el `.p12` lleva la clave privada.

## 10. Streaming de video por la VPN

- Evaluación: un enlace de 100 Mb/s (el perfil VPN de la CI) da ~10,5–11 MB/s útiles, muy por
  encima de lo que pide un video de 400–500 MB (entre 0,75 y 13 Mb/s según su duración).
- Ajustes de fluidez: lecturas que esperan más que una reconexión (75 s en vez de 15 s), lecturas
  canceladas por un salto que no siguen trabajando, y caché de las últimas ventanas chicas
  (cabecera e índice de MP4/MKV). Nueva prueba con el patrón de lectura de un reproductor.

## 11. Archivos .flac abiertos con otras apps (en curso)

- El usuario reporta que VLC no abre un `.flac` del NFS por la VPN y que, al abrirlo con el
  reproductor del sistema, Material Files se cierra.
- Nueva prueba en el emulador que hace lo mismo que otra app a través del `FileProvider`
  (consulta de nombre y tamaño, aperturas de sondeo, lecturas con saltos, dos descriptores a la
  vez, lectura completa): pasa en las seis combinaciones, así que el fallo no se reproduce dentro
  del proceso.
- Se agregó un registro de errores: si la app se cierra, el detalle queda en
  `Android/data/me.zhanghai.android.files.nfs/files/crash-log.txt`, para diagnosticar con el
  error real.

## 12. Streaming lento por la VPN: el límite estaba en TCP, no en el enlace

- Síntoma: un video de 500 MB y 1:30 esperaba ~10 s por segundo de reproducción.
- Primera causa (Material Files): la lectura anticipada caía a pedidos de 1 MB de a uno, porque
  los saltos del `FileProvider` (FUSE) se tomaban como saltos del reproductor y el ancho de banda
  se subestimaba. Se reescribió `FileByteChannel` con ventanas encoladas y caché en disco de
  bloques ya leídos (`NfsReadCache`, hasta 4 GB).
- Segunda causa (libnfs): en un enlace con pérdida y 100 ms de RTT una sola conexión TCP rinde
  poco, y los timeouts de libnfs vencían aunque los datos siguieran llegando. Parche 10: el
  timeout solo corre cuando la conexión deja de moverse. Nueva API de archivos repartidos en varias
  conexiones (`openStriped`, `readStriped`, `writeStriped`).
- Mediciones de referencia en la CI de libnfs-android (100 ms, 0,3 % de pérdida, 100 Mb/s): TCP con
  8 flujos 8,5 MB/s; cliente NFS del kernel 0,5–0,9 MB/s (con o sin nconnect); libnfs con 1
  conexión 0,4 MB/s, 16 conexiones 5,4 MB/s, 24 conexiones 6,0 MB/s, 32 conexiones 6,5 MB/s de
  lectura y 8,7 MB/s de escritura. Más pedidos en vuelo por conexión no ayudan; más conexiones sí.
  Sin pérdida y en Wi-Fi, libnfs rinde lo mismo que TCP.
- Material Files usa 32 conexiones extra para el streaming, con hasta 256 MB de lectura anticipada
  (nunca más de la mitad del heap).
- CI: pruebas repartidas en shards paralelos (por escenario y por grupo de pruebas); libnfs-android
  termina en ~6 minutos.
- Con 32 conexiones apareció `NFS4ERR_DELAY` al borrar un archivo recién leído: nfsd entregaba
  delegaciones de lectura a cada conexión de libnfs, que no puede devolverlas. libnfs 0.5.2 pide
  siempre abrir sin delegación. Las conexiones extra solo se abren si quedan 16 MB o más por leer.

## 13. Pipeline de bloques para VPN (lectura y escritura)

- Análisis de punta a punta: el lector esperaba el bloque de la conexión más lenta mientras las
  otras 31 quedaban paradas (cola fija por conexión que solo se rellenaba cuando el lector
  avanzaba), y las subidas iban por una sola conexión en tandas con espera.
- Medición en libnfs (VPN 100 ms, 0,3 %, 32 conexiones): 1 pedido en vuelo por conexión rinde
  igual que 8 (6,0 contra 6,6 MB/s); con 64 hilos de nfsd en el servidor, 10 MB/s de lectura y
  10,5 de escritura (con 8 hilos, 6 MB/s). Recomendación al usuario: `threads=64`.
- `FileByteChannel` reescrito: cada conexión toma el próximo bloque al liberarse, bloques
  atrasados duplicados, bloque urgente en partes de 256 KB, escrituras en paralelo con COMMIT
  final, reintentos con espera creciente (`NFS4ERR_DELAY`).
- Resultados en el emulador por VPN: primer byte de 2–5 s a 0,8–1,4 s; releer desde la caché de
  16 a ~90 MB/s; subidas de 0,3 a 1,2–2,2 MB/s con archivos de 8 MB (limitadas por pocos bloques:
  pasan a bloques de 256 KB).

## 14. Caché de disco sin huecos y pruebas de carga

- Problema: al volver a una parte ya vista después de varios saltos, se volvía a pedir al
  servidor. Causa: la caché guardaba solo bloques de 1 MB completos. Un bloque esperado se baja en
  8 partes de 128 KB y el lector sigue apenas llega la suya; si salta antes de que lleguen todas,
  el bloque se descartaba y no quedaba nada en disco. Además, las escrituras iban a una cola que
  descartaba bloques por encima de 64 MB, y el tope era min(4 GB, ¼ del libre).
- Cambios: cada parte se guarda en disco apenas llega y el lector arma el bloque con las partes
  guardadas (pide a la red solo lo que falta); escritura síncrona desde el hilo que bajó los datos,
  sin descartes; tamaño en GB en Configuración → NFS (0 la desactiva, nunca deja menos de 1 GB
  libre) con botón para borrarla; casilla por servidor "Usar la caché local" (`options-v4`, lee
  v2/v3); el resumen de cada archivo en `nfs-log.txt` dice cuánto salió de disco.
- Pruebas: la de saltos reabre el archivo y vuelve a los 40 lugares (deben salir de disco).
  Pruebas de carga con videos de tamaño real (250–700 MiB), acordadas con el usuario: película
  entera, buscar una escena, ráfagas de saltos y soltar, maratón de episodios, 3 reproductores,
  6 archivos saltando, un video con 3 descriptores, abrir/cerrar en ráfaga, saltos mientras se sube
  un archivo, caché tras un cambio y caché desactivada. Cada una verifica aciertos y fallos de
  caché esperados e informa saturación de conexiones (`ConnectionStats`), sin fugas ni cortes.
  Límites de buena experiencia: salto ≤ 1 s promedio y ≤ 3 s máximo, abrir ≤ 1,5 s, cerrar
  ≤ 300 ms, lo ya visto ≤ 100 ms, reproducción a 1 MB/s sin cortes.
- CI del emulador reducida a lo que usa el usuario: LAN mTLS y VPN sin TLS.

## 15. Pruebas de carga con videos reales y lo que revelaron

- Pruebas acordadas con el usuario (videos de 250–700 MiB, reproducción a 1 MB/s con 2 s de
  colchón, límites de buena experiencia) más verificación de aciertos/fallos de caché por archivo
  y de saturación de conexiones. CI en 8 grupos × LAN mTLS / VPN sin TLS.
- Primeras mediciones (LAN / VPN): película de 500 MB a 19 / 4 MB/s y releída de caché a 85 /
  120 MB/s; saltos de 107 / 152 ms; volver a lo visto 15–26 / 4 ms.
- Errores reales encontrados y corregidos:
  - Al reabrir un archivo cambiado o borrado por otro cliente se servía el motor compartido
    viejo (Android cierra los descriptores tarde): ahora se revalida la versión con GETATTR.
  - Fuga del pool: las conexiones extra se devolvían buscando el pool por la configuración del
    servidor; si se había editado o quitado, quedaban atadas para siempre y llenaban el límite
    (caché lenta, esperas y cortes en todo lo demás).
  - Con 6 archivos, 193–294 pedidos rechazados y conexiones propias compartidas (esperas de 10 s):
    reservadas según archivos abiertos y la propia nunca compartida.
  - Sin prioridad entre archivos, la lectura adelantada y las subidas de uno cortaban a otro
    (3 reproductores por VPN: 36 cortes; con prioridad, 11–13).
  - Bloques que un salto dejaba a medias no quedaban en caché: ahora cada parte de 128 KB se guarda.
- Falsos positivos de la medición corregidos: lectura adelantada del buffer fuera del rango,
  lecturas de FUSE alineadas a página, lectura en el fin de archivo.
- Estado: LAN pasa todo salvo cortes con 3 reproductores y con abrir/cerrar en ráfaga. VPN
  (emulador) tiene cortes de conexión en ráfaga; se sospecha de la red emulada.
- Cuelgue real encontrado con el perro guardián de las pruebas (6 min por prueba, volcado de
  hilos): una subida cuyo propio canal se cortaba al cerrar quedaba bloqueada para siempre
  (close() tenía el candado del canal esperando las escrituras; la escritura fallida lo pedía para
  marcarlo cerrado). Corregido; además cerrar falla si no queda conexión que pueda escribir.
- Cortes de conexión solo en VPN del emulador: en el host (sin emulador, mismo netem) nfsd no
  cerró ninguna conexión en 6 corridas de 20 s con respuestas de 1 MiB, y 256 KiB rinde 10 % menos
  (6,3 contra 7,0 MB/s con 16 conexiones): se mantiene 1 MiB. En el emulador, nfsd llegó a cortar
  una respuesta a medias ("sent N when sending M bytes - shutting down socket"): la red emulada
  (NAT de usuario de QEMU) no drena a tiempo.

## 16. Carga por VPN en el host, sin emulador

- Decisión del usuario (opción 3), con el ajuste de correr el motor de Material Files y no solo
  libnfs: los escenarios pasan a `sharedTest` y corren también en la máquina de la CI con
  Robolectric, libnfs compilado para el host, nfsd real y netem en loopback.
- Primera corrida completa por VPN fiel: movies, scenes, load y jumps pasan; 0 cortes de conexión
  en todas las pruebas; 3 reproductores sin cortes (saltos de 473 ms); 6 archivos saltando a
  362 ms de promedio (2,1 s en el emulador); saltos durante una subida sin cortes; volver a lo
  visto en 0–2 ms. Confirma que los cortes eran de la red del emulador.
- En el emulador por LAN mTLS los tiempos varían entre corridas (caché de 15 a 320 ms, cortes con
  3 reproductores): CPU del emulador con TLS por software y el proxy FUSE, no la red.
- Grupo host LAN mTLS (opción A del usuario): pasa todo; película a 408 MB/s, saltos de 5–14 ms,
  3 reproductores y subida en paralelo sin cortes, 0 conexiones cortadas. Los cortes y demoras del
  emulador por LAN mTLS son de su CPU (TLS por software) y del proxy FUSE, no del motor.
- Decisión del usuario: en el emulador los escenarios de carga informan tiempos y cortes sin
  exigirlos (su CPU y el proxy FUSE los fijan); los límites de experiencia se exigen en el host.
  Primera CI completa en verde: 23 trabajos en 13 minutos.

## 17. "getaddrinfo error 7": Android cortando la red en segundo plano

- Primer intento (revertido a pedido del usuario): libnfs-android 0.5.4 reconectaba a la última
  dirección si el nombre no resolvía y Material Files guardaba direcciones (`NfsAddresses`).
  Tapaba el síntoma. Se volvió a 0.5.3 (el commit de la reversión en libnfs-android dice 0.5.5, pero el
  número quedó en 0.5.3: mismo código que 0.5.3, no se publicó otra versión).
- Log siguiente del usuario: durante más de 2 minutos, toda conexión nueva fallaba al resolver, 8
  fallas en 30 ms (sin consultar a la red), las existentes se iban cayendo y no se podían
  reemplazar, con la VPN funcionando. Se recuperó sola cuando el usuario volvió a Material Files.
  En el log anterior: reproducir desde el principio andaba (conexiones hechas al abrir) y fallaban
  los saltos (conexiones nuevas).
- Causa: Android bloquea los pedidos de red nuevos de una app que considera en segundo plano
  (Android 15+ para toda app; antes con ahorro de datos o restricciones de batería), y Material
  Files lo está mientras el reproductor está en pantalla. La resolución es lo primero que falla.
- Solución: servicio en primer plano mientras haya conexiones NFS (la forma que Android da para
  seguir haciendo en segundo plano algo que el usuario pidió). `nfs-log.txt` anota los bloqueos
  que informa Android y si no pudo iniciarse el servicio.
- "Volver al principio seguía esperando": el principio salió de la caché de disco (42 MB leídos de
  disco, ninguna espera registrada cerca del inicio); la espera fue en 26,2 MB, más allá de lo
  leído antes del salto, que el reproductor pedía para su colchón, y la red estaba bloqueada.

## 18. Últimas funciones, evaluación y pendientes

- Notificación "Conectado a NFS" en su propio canal ("Conexión NFS", se oculta sin tocar las
  demás), con conexiones por rol, en uso y dormidas; tocarla abre el diagnóstico.
- "Leer por adelantado" por servidor (16 MB a 1 GB, 256 por defecto; `options-v5`). La CI detectó
  un cierre al leer la configuración (lista inicializada después del valor que la usaba):
  corregido antes de llegar al teléfono.
- Pantalla de diagnóstico: red y bloqueo de Android, servicio, tráfico en vivo, conexiones por
  rol, archivos abiertos, fallas, caché, espacio; "Probar el enlace" y "Compartir el log".
- Espacio y cuota: consultados solo al mostrarse (pedido del usuario: nada periódico) y antes de
  copiar; aviso si no entra; errores claros de sin espacio y cuota agotada.
- Carrera en la caché: un bloque de una versión vieja terminado de escribir justo después de
  borrar las versiones viejas quedaba en disco (nunca servido); ahora se borra solo. La última
  corrida de la CI con este arreglo no se confirmó (el usuario cortó el seguimiento).
- Intermitente: `throughput` en el emulador por VPN falla a veces con la conexión cortada en medio
  (2 de 4 corridas); probablemente la red emulada, sin confirmar. `rapidOpenCloseWhilePlaying`
  falló una vez por un dato servido de caché que la prueba creía nunca leído; sin investigar.
- Preguntas respondidas (sin cambios de código): la compresión no sirve para videos (ya
  comprimidos) y NFS/TLS/WireGuard no comprimen; lo útil sería transcodificar (Jellyfin) o
  re-codificar la biblioteca. La lectura adelantada ya se guarda en disco. Comparación con SFTP,
  FTPS, FTP, WebDAV y SMB: lo nuestro gana en rendimiento y resiliencia por VPN con pérdida y es lo
  más caro de mantener; WebDAV con rangos en paralelo sería la alternativa más cercana.
  Arquitectura: lo esperado en Android es un DocumentsProvider (SAF); se recomienda el modelo
  híbrido (acceso directo en Material Files + proveedor SAF en el mismo proceso, con la misma
  piscina y caché). Kotlin es adecuado: las líneas vienen de la planificación, no del lenguaje; el
  "bloqueo" de libnfs es elección del puente (un hilo por conexión esperando en `poll()`), sin
  costo de rendimiento.
- Idea a medir (no hecha): BBR en el servidor (`net.core.default_qdisc=fq`,
  `net.ipv4.tcp_congestion_control=bbr`) para que pocas conexiones llenen el enlace en lecturas;
  medir primero en los shards de baseline de libnfs-android CUBIC contra BBR con 1/4/8/16
  conexiones. Las subidas dependen del control de congestión del teléfono, que una app no elige.

## 19. Privacidad y cierre

- El repositorio es público: se agregaron a `CLAUDE.md` las reglas de privacidad (nada de la
  instalación del usuario en archivos, commits ni CI), los límites de buena experiencia, el estilo
  de código y los criterios de las pruebas. Se quitaron de esta bitácora y de los lineamientos los
  detalles de una instalación concreta, y se reescribió el historial de la rama para que no queden
  en versiones anteriores.
- El proyecto queda congelado como referencia. Sigue en `nfs-core` (núcleo en Rust y gateway
  HTTP/3 CONNECT sobre QUIC) y `nfs-android` (app nueva con DocumentsProvider).
