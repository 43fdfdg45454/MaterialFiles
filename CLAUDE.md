# CLAUDE.md — Material Files con NFS

Lineamientos vigentes del proyecto. La historia de cómo se llegó hasta acá está en
[BITACORA.md](BITACORA.md).

## Proyecto

- **Material Files (fork)**: `43fdfdg45454/MaterialFiles`, rama `nfs`. Agrega el tipo de
  almacenamiento "Servidor NFS" junto a FTP, SFTP, SMB y WebDAV.
- **libnfs-android**: `43fdfdg45454/libnfs-android`. Puente JNI sobre libnfs 8 publicado como
  AAR `io.github.libnfsandroid:libnfs`. El repositorio Maven vive en la rama `maven` y se
  consume desde `https://raw.githubusercontent.com/43fdfdg45454/libnfs-android/maven`.
  Se publica solo, desde la CI, cuando cambia `VERSION_NAME` en `gradle.properties` de `main`.
- Material Files depende de una versión concreta del AAR (`app/build.gradle`). Para usar una
  versión nueva: publicarla primero (CI verde en `main`) y recién después subir la dependencia.

## Estado

**Congelado.** Solo sirve de referencia y de línea base en las mediciones. El desarrollo sigue en
`43fdfdg45454/nfs-core` (núcleo en Rust y gateway) y `43fdfdg45454/nfs-android` (la app nueva).

## Forma de trabajo

- **Nada se ejecuta en la computadora del usuario.** Todo corre en el contenedor de la nube o en
  GitHub Actions.
- Respuestas en español; explicaciones completas cuando se pide detalle, sin relleno.
- Las sugerencias del usuario se evalúan antes de seguirlas: si no parecen el camino correcto, se
  dice por qué.
- Las optimizaciones de transporte se terminan y se miden primero en libnfs-android (CI más
  rápida); recién después se prueba Material Files.
- Commits con las líneas de atribución que indique la sesión.

## Privacidad: el repositorio es público

- **Terminantemente prohibido** publicar detalles de la instalación del usuario: IPs, nombres de
  host o dominios, rutas del servidor o de los exports, usuarios y UID/GID reales, modelo o sistema
  del teléfono, proveedor o ancho de banda contratado, topología de la red o de la VPN,
  certificados, claves, contraseñas o tokens. Vale para código, pruebas, documentación, mensajes
  de commit, issues, PRs y anotaciones o logs de la CI.
- Los ejemplos usan solo direcciones y nombres reservados para documentación: `192.0.2.0/24`,
  `198.51.100.0/24`, `203.0.113.0/24`, `2001:db8::/32` y `example.net` (RFC 5737, 3849, 2606).
  Las redes simuladas de la CI usan direcciones propias de la prueba.
- Certificados y claves de prueba: se generan en la CI en cada corrida y se descartan. Las claves
  reales van solo como secrets de GitHub.
- Lo que el usuario cuente de su servidor, su red o sus logs se usa en la conversación y nunca
  termina en un archivo, un commit ni un mensaje. La bitácora y estos lineamientos hablan de "el
  servidor" y de "un teléfono" en general.
- Antes de cada commit, revisar el diff buscando esos datos. Si algo se filtró, se corrige y se
  reescribe el historial (force push) en el momento.

## Buena experiencia de uso

Los límites son de buena experiencia, no de "funciona". Toda prueba de rendimiento los exige; donde
los fija la plataforma y no el código (la CPU del emulador), se informan sin exigirlos.

- Abrir un archivo (hasta el primer byte): ≤ 1,5 s.
- Salto dentro de un video: ≤ 1 s en promedio y ≤ 3 s siempre.
- Cerrar un archivo: ≤ 300 ms.
- Volver a algo ya visto: ≤ 100 ms (sale de la caché).
- Reproducción sin cortes: lector a 1 MB/s (1080p) con 2 s de colchón.
- Todo eso por el perfil VPN de referencia: 100 ms de RTT, 0,3 % de pérdida, MTU 1420, 100 Mb/s.

## Estilo de código

- La menor cantidad de código posible.
- Archivos de hasta 100 líneas. Es un límite blando: se pasa solo si dividir el archivo empeora su
  lectura.
- Responsabilidades claras: cada archivo, clase, objeto y modelo hace una cosa.

## Pruebas y CI

- Las pruebas tienen que ser lo más rápidas posible: se reutiliza todo lo que se pueda (entorno,
  servidor, fixtures, compilaciones en caché) y se paraleliza al máximo (matrices, shards, jobs).
- Los minutos de CI no tienen costo en un repositorio público: se usan todos los recursos que
  hagan falta, priorizando siempre el tiempo total por sobre el costo. 20 minutos es el máximo
  aceptable.
- Después de cada push, consultar el estado cada 30 segundos, nunca con esperas largas. Antes de
  ponerse a esperar, explicarle al usuario en un par de palabras qué cambió y qué se espera.
- Los logs de Actions no se pueden leer (403): los resultados se publican como anotaciones
  (`::notice` / `::error`, saltos de línea como `%0A`, sin comas en el título).
- En los scripts: `set -o pipefail` delante de cualquier `| tee`; con `sudo`, pasar `PATH` y
  `GITHUB_ACTIONS` explícitamente.

## libnfs-android

- El submódulo de libnfs **no se toca**. Todo cambio va como parche en
  `library/libnfs-patches.cmake` (`libnfs_patch` exige la cantidad exacta de coincidencias) o como
  archivo en `library/patches/` agregado con `libnfs_append`.
- **Solo NFSv4.2.** No hay soporte para NFSv3 ni se agrega.
- Parches vigentes: FSID alineado; errores de timeout (`-ETIMEDOUT`) y de RPC (`-EIO`); caché de
  respuestas de la sesión (`sa_cachethis` en toda operación que modifica estado,
  `ca_maxresponsesize_cached`); `RETRY_UNCACHED_REP` → reenvío automático con número de
  secuencia nuevo si la operación es idempotente, `-EALREADY` si no; COPY y CLONE (RFC 7862);
  hook de conexión para RPC-with-TLS; `O_EXCL` crea con `GUARDED4` (con `EXCLUSIVE4` el
  verificador quedaba como fecha del archivo); todo OPEN pide no recibir delegaciones
  (`WANT_NO_DELEG`: libnfs no tiene canal de callback y otros clientes recibían `NFS4ERR_DELAY`);
  16 slots por sesión.
- Transferencias: bloques de 1 MiB, hasta 16 RPC en vuelo; lecturas y escrituras en paralelo desde
  una sola llamada JNI.
- Timeouts (parche 10): solo vencen cuando la conexión deja de mover datos, no mientras los
  pedidos esperan en cola.
- Archivos repartidos (`openStriped`/`readStriped`/`writeStriped`): hasta 32 conexiones, 8 pedidos
  en vuelo por conexión. En enlaces con pérdida lo que suma es la cantidad de conexiones, no la
  profundidad por conexión.
- **RPC-with-TLS (RFC 9289)**: libnfs recibe un extremo de un socketpair y `NfsTlsTransport` (Java)
  hace el resto: sondeo STARTTLS, TLS 1.3, ALPN `sunrpc`, verificación del nombre contra el SAN.
  Una sesión TLS nueva por cada conexión o reconexión. **No fijar SO_SNDBUF/SO_RCVBUF en el
  socket TCP del relay**: Linux los recorta a ~200 KiB y desactiva el autoajuste (eso limitaba
  TLS a ~60 MB/s).
- Pruebas (`host-test/NfsHostTest.java`) contra nfsd real con `tlshd`, en shards paralelos:
  loopback, VPN simulada (netns + veth, 40 ms RTT, 0,3 % de pérdida, MTU 1420), resiliencia con y
  sin mTLS (corte de red, cambio de IP del cliente, respuestas perdidas de RENAME y REMOVE),
  benchmarks sin TLS, con TLS y con mTLS en Wi-Fi y en VPN, rechazos de TLS (sin certificado,
  CA desconocida, nombre que no coincide, TCP plano contra exports TLS). Shards `baseline-*`
  comparan TCP (iperf3), el cliente del kernel y libnfs con 1 a 32 conexiones en VPN con pérdida,
  VPN limpia y Wi-Fi.

## Material Files (NFS)

- Conexión: `autoReconnect(10)`, `resolveOnReconnect`, `retrans(0)`, timeout de 30 s, caché de
  directorios desactivada. `NetworkMonitor` reinicia las conexiones cuando cambia la red por
  defecto o sus direcciones (Wi-Fi ↔ datos, VPN).
- **Servicio en primer plano** (`NfsConnectionService`, `specialUse`) mientras haya alguna
  conexión NFS (se cierra cuando no queda ninguna, 5 min después del último uso), con notificación
  "Conectado a NFS" en su propio canal ("Conexión NFS", se puede ocultar sin tocar las demás):
  conexiones por rol (propias, reservadas para saltos, lectura adelantada y subidas, copias,
  libres), cuántas en uso (con una llamada en curso, conectar incluido) y cuántas dormidas;
  se actualiza cada 2 s como mucho. Android (15+ siempre; antes, con ahorro de datos o restricciones de batería)
  bloquea las conexiones nuevas de una app en segundo plano, y Material Files lo está mientras un
  reproductor muestra el video (si el reproductor se queda solo con el descriptor, el proceso
  pierde importancia): la resolución del nombre falla al instante (`getaddrinfo error 7`) y con
  ella toda conexión nueva y toda reconexión. Solo se puede iniciar con la app en pantalla o recién
  salida (se inicia con la primera conexión); si Android lo rechaza, se anota en `nfs-log.txt`, igual
  que los bloqueos de red que informa Android (`onBlockedStatusChanged`). Nada de direcciones
  guardadas ni de saltear el DNS.
- Copias dentro del mismo servidor: CLONE y, si no hay soporte, COPY en tramos de 64 MB con
  progreso. Si el servidor no puede, se copia a través del cliente.
- **Prioridad del proyecto: lectura (streaming, descargas) y escritura (subidas) por VPN con
  pérdida.** LAN/Wi-Fi no necesita optimización. Cada cambio se analiza antes (modelo del enlace,
  código de punta a punta) y se justifica con el impacto esperado; nada "por probar".
- Modelo del enlace: una conexión TCP con pérdida rinde ≈ MSS/RTT × 1,22/√p (≈0,3 MB/s a 100 ms y
  0,3 %). Solo muchas conexiones transfiriendo a la vez y sin pausas llenan el enlace; 1 pedido en
  vuelo por conexión alcanza (medido).
- `FileByteChannel` es un pipeline de bloques (lectura 1 MB, escritura 256 KB) sobre conexiones
  extra del pool (hasta 24 por export, +4 para reservadas; inactivas mueren a los 5 min; al abrir
  un archivo se conectan 17, todas las que usa, para no crear conexiones durante la reproducción). Cada
  conexión es un hilo `Worker` que toma el trabajo más útil al liberarse. Perfiles:
  - **STREAM** (abierto solo lectura): compartido por todos los descriptores del archivo
    (`ReadDescriptorChannel`, cada uno con su posición); al cerrar el último se cierra y todas sus
    conexiones vuelven al pool (un archivo cerrado no reserva nada). 4 conexiones reservadas (propia + 3)
    para lo que un lector espera ahora (bloque en 8 partes de 128 KB en paralelo) y para duplicar
    bloques atrasados; mientras el lector no lleva 8 MB seguidos, también leen adelante. Extra según
    lo leído desde el último salto: 4, 12 desde 1 MB, 16 desde 8 MB (cada conexión es un cliente
    NFSv4 con sesión, y las sesiones comparten memoria del servidor; libnfs pide 16 slots por sesión); reparto entre archivos en
    streaming (el que tiene de más devuelve las que leen adelante). Tras un salto: primeros 4 bloques
    en partes paralelas si el lector se queda 150 ms; lectura adelantada si se queda 300 ms o avanza
    1 MB. Un bloque esperado que viene entero y lleva 300 ms pasa a partes. Máximo 8 conectando a la
    vez, 1 s de pausa tras una falla; si no queda ninguna, reconecta una vez por segundo.
    Memoria: 48 MB adelante por archivo, 96 MB entre todos, 24 MB de bloques ya leídos. Disco:
    `NfsReadCache`, tamaño en Configuración → NFS (GB, 4 por defecto, 0 = desactivada, nunca deja
    menos de 1 GB libre; botón para borrarla) y casilla "Usar la caché local" por servidor
    (`options-v4`). Todo lo que llega de la red se guarda, sin cola ni descartes, desde el hilo que
    lo bajó: bloques enteros y también partes de 128 KB sueltas (un bloque abandonado por un salto
    queda a medias en disco; al completarse se guarda entero y se borran las partes). El lector
    busca memoria → disco (bloque o partes, en su propio hilo) → red; tras un salto también carga de disco los 3 bloques
    siguientes al instante (sin esperar los 150/300 ms); con 8 MB seguidos, colchón de 256 MB solo en
    disco. El colchón es la opción por servidor **"Leer por adelantado"** (`readAheadMb`,
    `options-v5`: 16–1024 MB, 256 por defecto): los primeros 48 MB en memoria, el resto solo en
    disco (sin caché, 48 MB como máximo).
  - Cierre: al cerrar el último descriptor, las extra terminan su pedido en curso (no se puede
    cancelar un RPC) y vuelven al pool; nada queda encolado. Los cierres corren en paralelo. Una
    conexión extra vuelve al pool que la contiene aunque el servidor se haya editado o quitado
    (antes quedaba atada para siempre y llenaba el límite).
  - Compartir un archivo abierto: un descriptor nuevo se engancha solo si el archivo en el
    servidor sigue siendo la misma versión (GETATTR; consistencia cerrar-abrir). Si cambió o ya
    no existe, se abre de nuevo o falla. La versión se toma al abrir.
  - Prioridad entre archivos: mientras un lector de otro archivo espera la red, cada archivo solo
    lee adelante 4 MB y las subidas bajan a 2 escrituras en vuelo.
  - Reservadas por archivo = límite del export / archivos abiertos − 1 (entre 1 y 4); las libres
    que sobran se devuelven. La conexión propia de un archivo nuevo nunca se comparte: si el pool
    está lleno se abre una por encima del límite (+4), que se cierra con el archivo.
  - Borrado o renombrado desde la app: se descarta su caché y se deja de guardar. Una versión
    nueva borra las viejas y las escrituras atrasadas de una versión vieja no se guardan. Si una
    extra encuentra el archivo borrado (ENOENT), no se piden más.
  - Por servidor (`ConnectionOptions`, formato `options-v4`): conexiones extra por archivo (4–32,
    16 por defecto; pool = ese número + 1 + 8) y crecimiento: todas al abrir, según lo reproducido
    (por defecto: 4, 3/4 tras 1 MB, todas tras 8 MB) o de a poco (+2 por segundo, conectándolas
    sobre la marcha). Salvo "de a poco", todas se conectan al abrir el archivo.
  - **THUMBNAIL** (`Client.thumbnailReads`): solo la conexión propia, de a 2 miniaturas; las
    miniaturas generadas quedan en la caché de disco de Coil.
  - **WRITE**: escrituras en paralelo (8/16/32 extra tras 1/2/4 MB, 64 MB pendientes), COMMIT al
    cerrar. Abrir para escribir, borrar, renombrar o copiar encima descarta el archivo compartido.
  - Errores: hasta 6 intentos con espera creciente (`NFS4ERR_DELAY`); una conexión rota no cierra un
    archivo de solo lectura. Lecturas con espera de 75 s.
  - `nfs-log.txt` en `Android/data/<paquete>/files/`: primer byte, esperas > 2 s, conexiones rotas,
    cambios de red, resumen por archivo. Pedirlo junto con `crash-log.txt`.
- **Diagnóstico de NFS** (`NfsDiagnosticsActivity`; desde la notificación y desde Configuración →
  NFS): red por defecto y si Android bloquea la de la app, servicio en primer plano, tráfico en
  vivo (1 s y 10 s, contadores `FileByteChannel.networkBytesRead/Written`), conexiones por servidor
  y rol, archivos abiertos, cortes y fallas recientes (`ConnectionStats`), uso de la caché. "Probar
  el enlace" (`Client.testLink`): 10 GETATTR de ida y vuelta y 8 s leyendo sin caché el último
  archivo abierto en ese servidor con tantas conexiones como usa un archivo (rol "prueba del
  enlace"). "Compartir el log" manda `nfs-log.txt`.
- **Espacio y cuota** (`NfsSpace`): `space_total/free/avail` del export (un GETATTR a la raíz),
  consultado solo cuando se va a mostrar (cada vez que el panel lateral dibuja el servidor, al
  abrir el diagnóstico; nunca periódico) y antes de una copia. El panel muestra "X libres de Y"
  (disponible para este cliente) con el último valor conocido hasta que llega la respuesta, solo
  pregunta si el servidor ya tiene conexiones (mostrar el panel no conecta a nada) y no vuelve a
  preguntar en los 2 s siguientes a una respuesta (la respuesta redibuja el panel). Copiar o mover
  hacia NFS consulta el espacio antes de empezar y, si no entra, pregunta ("Copiar igual" /
  cancelar); copias dentro del mismo servidor no se chequean (el servidor puede clonar). Errores
  claros: `NFS4ERR_NOSPC` "el servidor se quedó sin espacio", `NFS4ERR_DQUOT` "se agotó tu cuota"
  (libnfs lo reporta como `ERANGE`: se reconoce por el nombre). El nfsd de Linux no informa cuotas
  por usuario como atributo NFSv4 (van por rquotad, otro servicio sin cifrar); las cuotas de
  proyecto de XFS/ext4 y la de un dataset ZFS sí se ven en `space_avail`/`space_total`. El
  diagnóstico muestra el espacio por servidor.
- Leer un archivo no genera evento de modificación (provocaba recargas en bucle con miniaturas).
- Los límites de las pruebas son de buena experiencia de uso, no de "funciona": salto ≤ 1 s en
  promedio y ≤ 3 s siempre, abrir ≤ 1,5 s, cerrar ≤ 300 ms, volver a algo ya visto ≤ 100 ms,
  reproducción sin cortes (lector a 1 MB/s, 1080p, con 2 s de colchón).
- Cada prueba verifica aciertos y fallos de caché por archivo (`FileByteChannel.readStats`): lo ya
  leído no espera la red; lo nunca leído (ni leído adelante: fuera de −1/+64 MB de lo visitado, o
  +320 MB si la prueba reproduce, porque un salto dentro de la lectura adelantada cuenta como
  avance y 8 MB de avance activan el colchón de disco) sí; archivo cambiado y caché
  desactivada, siempre red.
- Cada prueba de carga informa las conexiones (`ConnectionStats`): pedidos rechazados por el
  límite del export, archivos frenados por el reparto, esperas con todas las conexiones ocupadas
  (faltan conexiones) o con alguna libre (el límite es el enlace o el servidor), pico usado del
  límite, cortes y respuestas de servidor ocupado. Exige: 5 s después de cerrar todo, ninguna
  conexión atada a un archivo sin una llamada en curso (eso sería una fuga; una lectura de 1 MB por
  VPN puede seguir), todas devueltas en 35 s, ninguna cortada ni que no pudo abrir, pico dentro del
  límite.
- Estadísticas para pruebas (no afectan el uso normal): `FileByteChannel.readStats` por archivo
  (esperas de red con su posición; una espera cuenta solo si los datos vinieron de la red),
  `ConnectionStats`, `Client.describeBoundConnections()` (dueño y rol de cada conexión atada).
  FUSE lee alineado a páginas: la espera de un salto puede quedar registrada hasta 128 KiB antes.
- Prueba de saltos: 40 saltos aleatorios (32 MiB) por la file provider con otro descriptor
  leyendo; después se reabre y se vuelve a los mismos lugares, que deben salir de disco.
- Los escenarios de carga están una sola vez en `app/src/sharedTest` (`NfsScenarios`) y corren en
  dos lugares: el emulador (`NfsProviderTest`, por la file provider/FUSE, LAN mTLS) y la máquina de
  la CI (`NfsHostLoadTest`, Robolectric, por el canal de la app, con netem en loopback: VPN sin TLS
  con 100 ms, 0,3 % de pérdida y MTU 1420, y LAN con 6 ms, sin pérdida, contra un export
  `xprtsec=mtls` con tlshd y el CA y certificado de cliente de la prueba). En el host, libnfs se compila para la máquina desde el commit
  de la versión del AAR (`LIBNFS_REF` en `nfs.yml`: actualizarlo junto con la dependencia), el
  reloj del motor es real (`NfsClock`; el `SystemClock` de Robolectric no avanza solo) y libnfs no
  se instrumenta (sus métodos nativos quedarían vacíos). Los límites de experiencia (tiempos y
  cortes) se exigen en el host; en el emulador solo se informan, porque ahí los fija su CPU (TLS por
  software) y el proxy FUSE. Contenido, caché, conexiones y cuelgues se verifican en ambos. Los tests del host se saltean sin
  `nfsHost`. Por VPN el emulador corre solo lo que no es carga (idle sin la prueba de saltos,
  stream, transfer): su red emulada corta conexiones bajo carga (medido: en el host, 0 cortes).
- Pruebas de carga con videos de tamaño real (fixtures en el servidor de la CI: películas de 250,
  500 y 700 MiB y tres episodios de 250 MiB; cada palabra = offset + etiqueta del archivo << 48),
  en los shards `movies` (película entera y releída de caché; maratón de episodios), `scenes`
  (buscar una escena; ráfagas de saltos y soltar; caché tras un cambio del archivo; caché
  desactivada), `load` (3 reproductores a la vez; un video con 3 descriptores; abrir y cerrar en
  ráfaga mientras otro reproduce), `jumps` (6 archivos saltando a la vez; saltos mientras se sube
  un archivo) y `changes` (borrado desde la app y desde otro cliente, modificado, agrandado y
  truncado por otro cliente mientras se reproduce; "otro cliente" es un contexto libnfs de la
  prueba). El emulador tiene 8 GB de disco para la caché. Tras las pruebas, la CI anota del lado
  del servidor reinicios de TCP y paquetes descartados por netem.
- La red del emulador en el perfil VPN tiene cortes en ráfaga bajo carga (varias conexiones a la
  vez, "Failed to reconnect", nfsd "shutting down socket" al enviar 1 MB) que no aparecen en el
  host con el mismo netem: es la red emulada (NAT de usuario de QEMU), no la app.
- **Seguridad de la conexión** (independiente de la identidad UID/GID): Ninguna, TLS o mTLS. Se
  confía en las CA del sistema y en las del usuario (`AndroidCAStore`). El certificado de cliente
  sale del almacén de claves de Android (`KeyChain`) y la clave nunca sale de ahí. Los errores de
  TLS se muestran con su causa.
- `ConnectionOptions` se guarda como parcel dentro de la lista de almacenamientos: su formato debe
  seguir leyendo los servidores guardados por versiones anteriores (marcador de formato).
- Los cierres inesperados quedan en `Android/data/<paquete>/files/crash-log.txt` (el build NFS no
  tiene reportes de errores y se trabaja sin adb): pedir ese archivo ante un cierre.
- Los archivos abiertos desde dentro de un archivo comprimido son copias temporales: el editor los
  abre en solo lectura.
- Builds: `debug` se mantiene; `nfsRelease` usa el id `me.zhanghai.android.files.nfs` y sale como
  `MaterialFiles-NFS-<versión>.apk`. Se firma con los secrets `NFS_KEYSTORE_BASE64` y
  `NFS_KEYSTORE_PASSWORD`; mientras no estén, con la clave provisoria pública del repositorio.
- CI: emulador LAN (Wi-Fi) mTLS × idle/stream/transfer/movies/scenes/load/jumps/changes, emulador
  VPN sin TLS × idle/stream/transfer, y host (VPN sin TLS y LAN mTLS) ×
  movies/scenes/load/jumps/changes, todo en paralelo (el perfil VPN usa MTU 1420 en loopback). La CI verifica que cada prueba esté en
  exactamente un shard del emulador y cada escenario en uno del host.

## Servidor (configuración recomendada)

- `net.ipv4.tcp_slow_start_after_idle = 0` en el servidor: una conexión quieta no vuelve a
  arrancar despacio (saltos y primer byte).
- `threads=64` en `[nfsd]` de `/etc/nfs.conf`: con los 8 por defecto, 32 conexiones hacen cola en el
  servidor (medido por VPN: 6 MB/s con 8 hilos, 10 MB/s con 64).
- Exports: `insecure` (Android usa puertos no privilegiados); `all_squash` con `anonuid`/`anongid`
  también en exports de solo lectura (define qué se puede leer). Las carpetas de **arriba** del
  export se atraviesan con el UID/GID del cliente: necesitan `o+x`. El bit setgid de una carpeta
  impone su grupo a lo que se crea adentro.
- `xprtsec=mtls` en el export. Si un export está **dentro de otro export real**, tiene que ser un
  punto de montaje propio (bind mount) para que nfsd aplique su política.
- `tlshd` ≥ 1.3: configuración en `/etc/tlshd/config`, `[authenticate.server]` con
  `x509.truststore` (solo la CA propia), `x509.certificate` (644) y `x509.private_key` (600).
  `x509.crl` para revocar certificados de cliente.
- Certificados: el SAN del servidor debe incluir exactamente el nombre o la IP que se usa en la app
  (sin dominio de búsqueda). Certificados de cliente: `extendedKeyUsage = clientAuth`, uno por
  dispositivo, `.p12` con `-legacy` y contraseña; la CA también se instala en Android como
  certificado de CA.
- VPN (WireGuard): filtrar por la IP del túnel de cada dispositivo, sin MASQUERADE del tráfico de
  los peers hacia el propio servidor.
