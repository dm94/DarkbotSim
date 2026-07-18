# DarkBot Simulator — Plan de construcción paso a paso

> Objetivo: construir un **simulador** al que se conecte el DarkBot
> (`https://github.com/darkbot-reloaded/DarkBot`) sin necesidad del juego real
> (DarkOrbit / Flash / `DarkTanos.so`), para poder probar el bot, sus módulos y
> plugins, y reproducir situaciones (NPCs, boxes, combate, mapas) bajo control.

---

## 0. Cómo funciona DarkBot por dentro (fundamentos)

Antes de diseñar el simulador hay que entender la cadena completa. DarkBot **no
habla con un servidor**: lee la memoria del proceso Flash (el navegador que
ejecuta `main.swf`). La cadena es:

```
DarkOrbit (HTTP)  →  Flash SWF en navegador  →  DarkTanos.so (hook nativo)  →  DarkBot (Java)
```

En Java, la frontera entre "el juego" y "el bot" es **una sola interfaz**:

- `com.github.manolo8.darkbot.core.IDarkBotAPI`  → interfaz raíz.
- `com.github.manolo8.darkbot.core.api.GameAPIImpl<W,H,M,E,I,D>`  → implementación
  base que delega en 6 sub-interfaces (`GameAPI.Window`, `Handler`, `Memory`,
  `ExtraMemoryReader`, `Interaction`, `DirectInteraction`).
- **Adapters** que extienden `GameAPIImpl`:
  - `core/api/adapters/TanosAdapter.java`  → usa `DarkTanos` (nativo, el real).
  - `core/api/adapters/KekkaPlayerAdapter.java`.
  - `core/api/adapters/NoopAPIAdapter.java`  → **no-op, "útil para testing"**.
    Esta es nuestra plantilla.

### Punto de selección del API (cómo se elige el adapter)

En `Main`:

```java
API = configManager.getAPI(pluginAPI);
```

Y en `config/ConfigManager.getAPI(...)`:

```java
BrowserApi api = params.useNoOp() ? BrowserApi.NO_OP_API
                                  : config.BOT_SETTINGS.API_CONFIG.BROWSER_API;
return pluginApi.requireInstance(api.clazz);
```

`BrowserApi` es un enum donde cada valor lleva una `.clazz`. El adapter debe
estar registrado como feature/singleton en el `PluginAPI` (igual que
`NoopAPIAdapter`). **Este es el único punto de extensión que necesitamos**:
añadir un valor `SIMULATOR_API` al enum y registrar nuestra clase.

### Cómo el bot "encuentra" el juego: `BotInstaller`

`core/BotInstaller.java` es el bootstrap. Busca un patrón de bytes en la
memoria y, a partir de ahí, sigue **offsets fijos** para localizar los objetos
raíz del juego. El contrato exacto (extraído del código) es:

| Búsqueda / lectura | Resultado |
|---|---|
| `searchPattern(bytesToMainApplication)` | `mainApplicationAddress = result - 228` |
| `readInt(mainApp + 4)` | `SEP` (separador de punteros AVM2) |
| `readLong(mainApp + 1344)` | `mainAddress` |
| `readLong(mainAddress + 504)` | `screenManagerAddress` |
| `readLong(mainAddress + 512)` | `guiManagerAddress` |
| `readLong(mainAddress + 560)` | `connectionManagerAddress` |
| `readLong(screenManager + 0)` | `SCRIPT_OBJECT_VTABLE` |
| `readLong(screenManager, 0x10,0x28,0x8,0x3e8,0x0)` | `STRING_OBJECT_VTABLE` |
| `searchClassClosure(settingsPattern)` | `settingsAddress` |

`settingsPattern` exige: `int(+48) == -1`, `int(+52) == 0`, `int(+56) == 2`,
`int(+60) == 1`.

A partir de esos raíces, los **managers** (`MapManager`, `HeroManager`,
`GuiManager`, `StatsManager`, `EffectManager`, `RepairManager`...) leen cada tick
sus offsets dentro de esos objetos para rellenar las entidades (`Ship`, `Box`,
`MapNpc`, `Pet`, etc.) que después consumen los módulos/plugins vía
`eu.darkbot.api.*`.

### Capacidades (`Capability`)

El adapter declara qué soporta vía `Capability` (enum en
`core/api/Capability.java`): `LOGIN`, `ATTACH`, `BACKGROUND_ONLY`,
`DIRECT_MOVE_SHIP`, `DIRECT_ENTITY_SELECT`, `DIRECT_COLLECT_BOX`,
`DIRECT_CALL_METHOD`, etc. El `Main` y `BotInstaller` **ramifican su lógica
según estas flags** (p. ej. con `BACKGROUND_ONLY` se saltan los chequeos de
ventana/refresh). Clave: el simulador debe declarar `BACKGROUND_ONLY` para no
depender de navegador ni de login.

---

## 1. Estrategia elegida

De las tres opciones posibles:

| Estrategia | Descripción | Veredicto |
|---|---|---|
| **A. Memoria falsa** | Un `SimulatorAdapter` sirve una memoria fake que cumple el contrato de `BotInstaller` y de los managers. Se reutiliza **todo** el core. | ✅ **Elegida** |
| B. Managers simulados | Reimplementar `MapManager`, `HeroManager`, etc. contra un modelo de mundo. | ❌ Trabajo enorme, duplica lógica. |
| C. Servidor fake | Falsificar el backend HTTP de DarkOrbit + el SWF. | ❌ Requiere Flash y volver a cifrar `main.swf`. Imposible hoy. |

**Por qué A:** es el camino de invasión mínima. `NoopAPIAdapter` ya demostró
que un adapter custom funciona; solo devuelve ceros y por eso el bot queda
"invalid". Si nuestra memoria fake es coherente, **el bot entero (módulos,
plugins, behaviors) funciona sin tocar una línea del core**.

El coste real de A es construir la **memoria fake**: un mapa
`dirección → bytes` con el layout AVM2/Flash que los managers esperan. Es un
conjunto finito y descubrible de offsets (leyendo el código de cada manager).
Empezamos por los justos para arrancar y ampliamos según necesidad.

> Principio lazy (YAGNI): no simulamos TODO el juego. Simulamos **solo los
> campos que DarkBot lee** para los módulos que queramos probar. El resto queda
> en 0/false hasta que algo lo pida.

---

## 2. Arquitectura del simulador

```
┌──────────────────────────────────────────────────────────────────┐
│  JVM de DarkBot                                                   │
│                                                                   │
│  Módulos / Plugins  ──usan──▶  eu.darkbot.api.*  (sin cambios)   │
│         │                                                         │
│         ▼                                                         │
│  core (managers, entidades)  ──leen──▶  IDarkBotAPI.Memory       │
│         │                                                         │
│         ▼                                                         │
│  ┌──────────────────────────────────────────┐                    │
│  │  SimulatorAdapter  ( = GameAPIImpl )     │                    │
│  │   Window/Handler/Interaction = no-op     │                    │
│  │   Memory         ──lee──┐                │                    │
│  │   DirectInteraction ────┼─escribe──┐     │                    │
│  └─────────────────────────┼──────────┼─────┘                    │
│                            ▼          ▼                          │
│  ┌──────────────────────────────────────────┐                    │
│  │  SimWorld  (modelo de mundo en JVM)      │                    │
│  │   - Hero (pos, hp, speed, target...)     │                    │
│  │   - NPCs[]   (id, pos, hp, tipo)         │                    │
│  │   - Boxes[]  (id, pos, tipo, ore)        │                    │
│  │   - Mapa actual, bounding box            │                    │
│  │   - tick(): mueve hero hacia destino,    │                    │
│  │              aplica daño, etc.           │                    │
│  └──────────────────────────────────────────┘                    │
│                            ▲                                     │
│                            │                                     │
│  ┌──────────────────────────────────────────┐                    │
│  │  FakeMemory  (layout AVM2 fake)          │                    │
│  │   - asigna direcciones estables a objetos│                    │
│  │   - sirve los offsets que leen los        │                    │
│  │     managers, poblados desde SimWorld     │                    │
│  │   - responde a searchPattern/queryBytes   │                    │
│  └──────────────────────────────────────────┘                    │
│                                                                  │
│  ┌──────────────────────────────────────────┐                    │
│  │  ScenarioRunner  (scripts de pruebas)    │                    │
│  │   spawn npc/box, cambiar mapa, mover...  │                    │
│  └──────────────────────────────────────────┘                    │
└──────────────────────────────────────────────────────────────────┘
```

Todo vive en la **misma JVM** que DarkBot (v1). Comunicación por llamadas
directas. Si más adelante queremos aislar, se separa por socket; pero eso es
YAGNI hoy.

---

## 3. Componentes a construir

1. **`SimulatorAdapter`** — extiende `GameAPIImpl`. Mismos parámetros genéricos
   que `NoopAPIAdapter`. Capabilities: `BACKGROUND_ONLY` + los `DIRECT_*` que
   implementemos.
2. **`SimWorld`** — el modelo de mundo (POJOs) + bucle de tick.
3. **`FakeMemory`** — el mapa de memoria que cumple el contrato de
   `BotInstaller` y sirve los offsets de los managers.
4. **`SimulatorDirectInteraction`** — implementa `moveShip`, `selectEntity`,
   `collectBox`, `lockEntity`, `callMethod` mutando `SimWorld`.
5. **`ScenarioRunner`** — API para crear NPCs/boxes, cambiar mapa y disparar
   eventos. Pensado para tests y para un panel manual.
6. **Registro del adapter** — entrada `SIMULATOR_API` en `BrowserApi` y
   feature注册 del adapter.

---

## 4. Fases paso a paso

Cada fase deja un entregable **verificable** antes de pasar a la siguiente.

### Fase 0 — Preparación del entorno

**Objetivo:** compilar y ejecutar DarkBot sin tocar nada, para tener línea base.

- [ ] Hacer fork/clone de `darkbot-reloaded/DarkBot`.
- [ ] `gradle clean build` (JDK 9+). Resolver dependencias.
- [ ] Ejecutar con `Main` usando `-noOp` (o config con `BROWSER_API = NO_OP_API`).
  Verificar que arranca la GUI y se queda en estado "invalid" sin crash.
- [ ] Leer y anotar: `IDarkBotAPI`, `GameAPI`, `GameAPIImpl`, `NoopAPIAdapter`,
  `BotInstaller`, `Main` (constructor + `tick()`).
- [ ] Confirmar la versión actual de offsets en `BotInstaller` (pueden cambiar
  entre versiones; **siempre anclar el simulador a un commit concreto de
  DarkBot**).

**Verificación:** `./gradlew run` con NoOp abre la ventana del bot.

---

### Fase 1 — Esqueleto del `SimulatorAdapter` (llegar a "valid")

**Objetivo:** registrar el adapter y conseguir que `BotInstaller.isInvalid()`
devuelva `false` (bot "conectado").

- [ ] Crear paquete `com.github.manolo8.darkbot.core.api.simulator` (o dentro de
  `core/api/adapters`).
- [ ] Copiar `NoopAPIAdapter` como `SimulatorAdapter` y:
  - Capabilities: `BACKGROUND_ONLY` (sin LOGIN, sin ATTACH, sin ventana real).
  - `NoOpHandler.isValid()` → `true`.
  - `getVersion()` → `"sim-0.1"`.
- [ ] Añadir `SIMULATOR_API` al enum `config/types/suppliers/BrowserApi.java`
  con `.clazz = SimulatorAdapter.class`.
- [ ] Registrar `SimulatorAdapter` como feature/singleton en el `PluginAPI`
  (mismo mecanismo que `NoopAPIAdapter`). Revisar cómo se instancian los
  adapters existentes (`@Feature`/`@Instantiable` o registro explícito en el
  setup del PluginAPI).
- [ ] Configurar `BOT_SETTINGS.API_CONFIG.BROWSER_API = SIMULATOR_API`.

**Verificación:** el bot arranca, `isValid()` es `true`, pero `BotInstaller`
sigue sin encontrar patrón → los managers no se instalan. Es esperado: es el
estado del que parte la Fase 2.

---

### Fase 2 — `FakeMemory`: cumplir el contrato de `BotInstaller`

**Objetivo:** que `BotInstaller.tryInstall()` complete y los managers se
instalen sin excepciones.

- [ ] Implementar `FakeMemory implements GameAPI.Memory` con un
  `Map<Long, byte[]>` (o un `ByteBuffer` asignado en un rango alto, p. ej.
  `0x1_0000_0000L` en adelante) más un índice de pattern search.
- [ ] Crear el **layout raíz** cumple-contrato:
  - Asignar `mainAppAddr`, `mainAddr`, `screenMgrAddr`, `guiMgrAddr`,
    `connMgrAddr`.
  - Sembrar el patrón `bytesToMainApplication` en una dirección `P`, de modo que
    `searchPattern(...)` devuelva `P` y `mainAppAddr = P - 228`.
  - Escribir en los offsets: `+4 → SEP`, `+1344 → mainAddr`, `mainAddr+504 →
    screenMgr`, `mainAddr+512 → guiMgr`, `mainAddr+560 → connMgr`.
  - En `screenMgr+0` un valor inventado pero estable para `SCRIPT_OBJECT_VTABLE`.
  - Un objeto "settings" que cumpla `settingsPattern` (offsets 48/52/56/60).
- [ ] Implementar `searchPattern(byte...)`, `queryInt/queryLong/queryBytes`:
  devolver las direcciones sembradas (v1: un único resultado conocido).
- [ ] Implementar `searchClassClosure(LongPredicate)` en `ExtraMemoryReader`
  para devolver el `heroInfoAddress` (cumpliendo el predicado
  `heroId == +0x30`, `level (+0x34)` en rango, `bool (+0x3c)` ∈ {1,2},
  `val (+0x40) == 0`, etc.).
- [ ] Sustituir el `NoOpMemory` del adapter por `FakeMemory`.

**Verificación:** con logs en `BotInstaller`, las direcciones se resuelven;
`isInvalid()` pasa a `false` de forma estable; el bot pasa a ejecutar
`validTick()` sin excepciones durante N ticks.

> Nota: los managers leerán muchos offsets que aún son 0. Eso está bien: las
> entidades estarán vacías. Lo importante es **no crashear**.

---

### Fase 3 — `SimWorld` + entidades visibles (NPCs y boxes)

**Objetivo:** que el bot "vea" NPCs y boxes en el mapa.

- [ ] Leer `MapManager` y anotar **exactamente** qué offsets lee para:
  - lista de entidades (vector/puntero + count),
  - por entidad: `id`, `x`, `y`, `type` (ship/npc/box), `hp`, `shd`,
    `clickable`, `address`/vtable para identificar el tipo.
- [ ] Leer `HeroManager` para los offsets del héroe (pos, hp, shd, speed, target,
  cargo, mapId).
- [ ] Diseñar `SimWorld`:
  ```
  SimWorld
    Hero hero
    List<SimNpc>  npcs
    List<SimBox>  boxes
    int mapId; double mapWidth, mapHeight
    void tick(long dt)
  ```
- [ ] Implementar `FakeMemory.bind(SimWorld)`: cada tick, vuelca el estado de
  `SimWorld` a las regiones de memoria que leen los managers (escritura perezosa
  o en cada `tick()` del adapter).
- [ ] Crear unNPC y un box de prueba hardcoded en `SimWorld` y comprobar que
  aparecen en la GUI del bot.

**Verificación:** el bot muestra 1 NPC y 1 box en su lista de entidades;
seleccionarlos no crashea (aunque atacar aún no hace nada).

---

### Fase 4 — `DirectInteraction` conectada al mundo

**Objetivo:** que las acciones del bot muevan el mundo simulado.

- [ ] Implementar `SimulatorDirectInteraction`:
  - `moveShip(Locatable)` → `hero.setDestination(x,y)`.
  - `lockEntity(id)` / `selectEntity(entity)` → `hero.target = id`.
  - `collectBox(box)` → marcar box para recoger cuando el hero esté en rango.
  - `callMethod(index, args...)` → mapear los `index` que usa `TanosAdapter`
    (p. ej. index 10 = goto) a acciones de `SimWorld`.
- [ ] En `SimWorld.tick()`:
  - mover `hero` hacia su destino a `hero.speed` (pinch de daño/combate simple
    opcional en esta fase).
  - al llegar a un box marcado, eliminarlo y sumar ore/cargo.
  - si hay target NPC, aplicar daño por tick y al morir eliminarlo.

**Verificación:** cargar un módulo simple (p. ej. un collector de boxes de
ejemplo) y ver que el héroe se desplaza y recoge el box.

---

### Fase 5 — Sistema de escenarios (NPCs/boxes a medida)

**Objetivo:** poder definir situaciones de prueba de forma cómoda.

- [ ] `ScenarioRunner` con API fluida en Java:
  ```java
  sim.scenario(s -> s
      .map(1)                       // mapear a X-1
      .hero(1000, 2000, speed=400)
      .npc("Streuner", 1500, 2000, hp=1000)
      .npc("Lordakia", 3000, 2500, hp=1500)
      .box("prometium", 1200, 2050)
      .onNpcDead(id -> s.spawn("Boss", ...))
      .run());
  ```
- [ ] Formato declarativo (JSON/YAML) para que no haga falta recompilar:
  `scenarios/streuners.json`.
- [ ] Triggers: timer, muertes, posición del héroe, hotkey desde la GUI.
- [ ] Movimiento de NPCs (waypoints / IA mínima) para probar persecución y huida.
- [ ] Cambio de mapa simulado (saltar a base y volver) para probar portales.

**Verificación:** cargar un escenario, ejecutar un módulo de farmeo y ver que
se comporta como se espera; modificar el JSON y re-ejecutar sin recompilar.

---

### Fase 6 (opcional) — Inspector / panel visual

- [ ] Ventana pequeña (Swing, reutilizando `MainGui`) o página web local que
  muestre el `SimWorld` en tiempo real (posiciones, HP, target).
- [ ] Controles manuales: botón "spawn NPC", "spawn box", "mover héroe a",
  "matar todo".
- Solo si aporta valor para depurar; si la GUI del propio bot basta, saltar.

---

## 5. Riesgos y decisiones técnicas

- **Los offsets cambian entre versiones de DarkBot.**
  → Anclar el simulador a un commit. Extraer offsets a una clase
  `Offsets` constante, documentada y versionada. Cuando se actualice DarkBot,
  regenerar `Offsets` leyendo `BotInstaller` + managers.
- **`BotInstaller` puede hacer comprobaciones que no conocemos.**
  → Mantener un test de humoke: arrancar y tras 5s `isInvalid()==false`. Ese
  test es el "check" que pide el modo lazy: lo más pequeño que falla si rompes
  el contrato de memoria.
- **`searchClassClosure` con predicado opaco.**
  → El predicado del hero (`checkUserData`) es legible; basta construir un
  objeto hero que lo pase. Documentarlo.
- **Performance:** el volcado de `SimWorld → FakeMemory` cada tick.
  → v1: escritura completa sencilla. Optimizar solo si el profiler lo pide.
- **No reimplementar Flash.** Si un plugin usa algo que no modelamos (p. ej.
  skills complejas), ese plugin fallará; es aceptable y se cubre ampliando
  `FakeMemory`/`SimWorld` bajo demanda.
- **Plugins que dependen de `core` directamente (no de `eu.darkbot.api`).**
  → Funcionarán igual porque el core está intacto; solo leen otra memoria.

---

## 6. Estructura de carpetas propuesta

```
DarkbotSim/
├─ PLAN.md                       ← este documento
├─ darkbot/                      ← submodule del fork de DarkBot anclado a un commit
└─ simulator/                    ← código del simulador
   ├─ src/main/java/.../simulator/
   │   ├─ SimulatorAdapter.java
   │   ├─ SimWorld.java
   │   ├─ FakeMemory.java
   │   ├─ Offsets.java           ← contract de offsets (versionado)
   │   ├─ SimulatorDirectInteraction.java
   │   └─ ScenarioRunner.java
   └─ src/test/java/.../
       └─ SmokeTest.java         ← arranca adapter y valida isInvalid()==false
```

El simulador se publica como jar que se añade al classpath de DarkBot (o como
plugin), y el adapter se selecciona por config. **Cero cambios en el core de
DarkBot salvo el enum `BrowserApi`** (y, si se prefiere no tocar DarkBot, se
registra el adapter por reflexión/plugin en lugar de editar el enum).

---

## 7. Orden recomendado de ejecución (resumen)

1. **Fase 0** → línea base.
2. **Fase 1** → adapter registrado, bot "valid".
3. **Fase 2** → `BotInstaller` completo, smoke test verde.
4. **Fase 3** → mundo con NPCs/boxes visibles.
5. **Fase 4** → acciones del bot mueven el mundo.
6. **Fase 5** → escenarios reutilizables.
7. **Fase 6** → inspector (solo si hace falta).

Cada fase es independiente y verificable. Se puede parar en cualquier punto y
ya tener algo útil.
