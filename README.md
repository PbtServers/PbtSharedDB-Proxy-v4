# PbtSharedDB (Proxy de PbtServers v4)

**Servidor MariaDB integrado para Velocity Proxy 4.x**

PbtSharedDB es un proyecto de PbtServers v4 cuyo objetivo es desarrollar un plugin para Velocity que permita ejecutar y gestionar una instancia MariaDB desde el propio proxy de Minecraft.

El propósito es compartir bases de datos entre múltiples servidores Minecraft alojados en Pterodactyl, evitando tener que administrar una instancia MariaDB independiente.

## Objetivos

- Integración nativa con Velocity Proxy 4.x.
- Inicio y apagado automático de MariaDB desde el proxy.
- Almacenamiento persistente de bases de datos.
- Compatibilidad con LuckPerms y otros plugins que utilicen MySQL/MariaDB.
- Conexiones SQL entre contenedores de Pterodactyl.
- Gestión de usuarios y bases de datos.
- Configuración de puerto y conexiones permitidas.
- Copias de seguridad y recuperación de datos.
- Comandos administrativos desde Velocity.
- Funcionamiento sin necesidad de un Egg exclusivo para MariaDB.

## Funcionamiento previsto

PbtSharedDB gestionará un proceso nativo de MariaDB dentro del contenedor donde se ejecuta Velocity.

Los servidores backend, como Survival, Creative y Lobby, se conectarán mediante TCP a la instancia MariaDB del proxy.

Cada plugin podrá utilizar su propia base de datos, y los plugins que necesiten compartir información podrán conectarse a una misma base de datos.

## Compatibilidad prevista

| Componente | Tecnología |
|---|---|
| Proxy | Velocity 4.x |
| Lenguaje | Java |
| Plataforma | Pterodactyl / Wings |
| Motor SQL | MariaDB |
| Clientes | MySQL/MariaDB |
| Compilación | Gradle |
| Sistemas | Linux |
| Proyecto | PbtServers v4 |

## Requisitos técnicos

La implementación deberá incluir o preparar binarios nativos de MariaDB compatibles con las imágenes Docker utilizadas en Pterodactyl.

También será necesario disponer de almacenamiento persistente, un puerto TCP asignado y conectividad entre los contenedores.

La base de datos dependerá de que el contenedor del proxy permanezca en ejecución.

## Seguridad

Las conexiones estarán protegidas mediante usuarios y contraseñas SQL. El acceso deberá restringirse a los servidores autorizados de la red interna.

Los datos y credenciales no se publicarán en GitHub.

## Estado del proyecto

**Fase de planificación — pendiente de implementación con Codex.**

Versión objetivo inicial: `1.0.0-SNAPSHOT`.

## Desarrollo

Desarrollado para **PbtServers v4**.

Organización: PbtServers.
