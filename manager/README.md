<div align="center">

# everydatabase-manager

### Typed references + caching, in front of EveryDatabase.

A thin façade over [`everydatabase-core`](../README.md): hold a **typed reference** to an entity
that lives in another collection (and even another **database**), cache the hot ones with a policy
you control, and resolve them lazily — without turning the library into an ORM.

</div>

---

## Table of contents

- [Why](#why)
- [Install](#install)
- [The 30-second version](#the-30-second-version)
- [Core concepts](#core-concepts)
- [`Ref<K, V>` — a reference that serializes as its key](#refk-v--a-reference-that-serializes-as-its-key)
- [`CachingManager` — the cache-backed façade](#cachingmanager--the-cache-backed-façade)
- [Freshness vs capacity](#freshness-vs-capacity)
- [Per-reference policy (`@RefPolicy`)](#per-reference-policy-refpolicy)
- [Writing & deleting through the cache](#writing--deleting-through-the-cache)
- [Nested references](#nested-references)
- [⭐ One entity, many databases, many key types](#-one-entity-many-databases-many-key-types)
- [What it guarantees (and what it doesn't)](#what-it-guarantees-and-what-it-doesnt)
- [Non-goals](#non-goals)

---

## Why

Core stores **self-contained** entities (`Key → ComplexEntity`) and is deliberately thin and async:
every read hits the backend, there is no value cache, and `find` returns a **fresh** instance each
time. That's correct for the core — but real applications want two more things:

1. **Typed references between entities.** Hold a `Guild`, not a raw `UUID` — while still storing only
   the key on disk.
2. **Caching of hot data.** The same guilds/definitions are read thousands of times; you shouldn't hit
   the backend every time.

Both are *façade* concerns, so they live in this optional module. The key idea: **a reference is not a
cache.** `Ref` is a pointer; the cache lives in the `CachingManager`. Keeping them separate is what
makes the design composable — and what lets a reference cross backends transparently.

---

## Install

`everydatabase-manager` depends on `everydatabase-core` (the recommended flavor), so adding it pulls
the full backend set transitively.

**Gradle**

```groovy
repositories {
    maven { url 'https://maven.petrus.dev/public' }
    mavenCentral()
}

dependencies {
    implementation 'br.com.finalcraft.everydatabase:everydatabase-manager:1.0.1'
}
```

**Maven**

```xml
<dependency>
  <groupId>br.com.finalcraft.everydatabase</groupId>
  <artifactId>everydatabase-manager</artifactId>
  <version>1.0.1</version>
</dependency>
```

---

## The 30-second version

```java
// 1. An entity that references another by a typed Ref (serialized as just the key on disk).
@Data @NoArgsConstructor @AllArgsConstructor
public class Player {
    private UUID uuid;
    private Ref<UUID, Guild> guild;   // -> "guild":"<uuid>" in the stored JSON
}

// 2. A manager per entity type. It self-registers, so any Ref<?, Guild> resolves through it.
EntityDescriptor<UUID, Guild> GUILDS = EntityDescriptor.builder(UUID.class, Guild.class)
        .collection("guilds")
        .keyExtractor(Guild::getId)
        .codec(RefCodecs.json(Guild.class))         // ref-aware Jackson codec
        .build();

CachingManager<UUID, Guild> guilds =
        new CachingManager<>(GUILDS, storage, CachePolicy.always());

// 3. Resolve a reference — it goes through the Guild manager (its cache, its backend).
Player p = playerRepo.find(playerId).join().orElseThrow();
Optional<Guild> g = p.getGuild().peek();          // synchronous, cache-only (the hot path)
p.getGuild().resolve().thenAccept(opt -> ...);    // async: cache hit, or load-and-cache
```

`peek()` never does I/O; `resolve()` loads on a miss. The `Player` neither knows nor cares where its
`Guild` lives or how it's cached.

---

## Core concepts

| Type | Role |
|---|---|
| **`Ref<K, V>`** | A typed, lazily-resolved reference. Serializes as its **key**; the target type `V` is recovered from the field declaration on read. |
| **`CachingManager<K, V>`** | A cache-backed façade in front of one `Repository`. Owns an in-memory identity map and resolves `Ref`s to that type. Self-registers in `Refs`. |
| **`Refs`** | Process-wide registry: entity type → its manager. A `Ref` finds its manager here. |
| **`CachePolicy`** | Freshness strategy: `always()`, `ttl(Duration)`, `noCache()`. Per-reference overridable. |
| **`CacheOptions`** | Store-level config: default `CachePolicy` + `maxSize` (LRU bound). |
| **`@RefPolicy`** | Per-field freshness override declared right on a `Ref` field. |
| **`RefCodecs`** | Builds a Jackson codec that understands `Ref` fields (`RefCodecs.json(Type.class)`). |

The naming reflects the split: the **`Ref*`** family is *the reference*; the **`Cache*`** family is
*the cache*.

---

## `Ref<K, V>` — a reference that serializes as its key

On disk a `Ref<UUID, Guild> guild` is just `"guild":"<uuid>"` — byte-for-byte what storing the raw
`UUID` would produce. No embedded entity, no `@JsonIgnore` tricks. The target type `V` is recovered
from the field's generic declaration at deserialization (via a Jackson `ContextualDeserializer`), so
the JSON stays clean and the Java side stays typed.

Resolution goes through the entity type's manager (looked up in `Refs`):

| Call | Blocking? | I/O? | Returns |
|---|:---:|:---:|---|
| `ref.peek()` | sync | never | `Optional<V>` — cache-only; empty if absent/stale |
| `ref.resolve()` | async | on miss | `CompletableFuture<Optional<V>>` |
| `ref.join()` | blocks | on cold miss | `V` or `null` (convenience) |

After the first resolution a `Ref` **memoizes the live cache cell**, so later `peek()`/`resolve()`
read it directly — **lock-free, no map lookup** — and, because the cell updates in place, the handle
always observes the latest value. A long-held `Ref` (e.g. on an online player) behaves like a
self-refreshing live handle.

Build a `Ref` programmatically with `Ref.of(key, Type.class)`; an empty reference is `Ref.empty(Type.class)`
(a JSON `null` round-trips to an empty `Ref`, never a bare `null`).

> **Codec.** Wrap entities that contain `Ref` fields with `RefCodecs.json(Type.class)` — it registers
> the `RefModule` on the mapper. (Equivalent to passing your own `RefModule`-registered `ObjectMapper`
> to `JacksonJsonCodec`.) Targets without `Ref` fields can use a plain `JacksonJsonCodec`.

---

## `CachingManager` — the cache-backed façade

Create one manager per entity type at startup; it self-registers in `Refs` (keyed by the entity type),
so every `Ref<?, ThatType>` resolves through it. Subclass it for a domain name, or use it directly:

```java
public final class GuildManager extends CachingManager<UUID, Guild> {
    public GuildManager(Storage storage) {
        super(GUILDS, storage, CacheOptions.builder()
                .policy(CachePolicy.always())   // small hot set: keep resident
                .maxSize(1000)                  // ...but bounded
                .build());
    }
}
```

Surface:

| Method | What it does |
|---|---|
| `resolve(key)` / `peek(key)` (+ `(key, policy)`) | the value-level read API (used by `Ref`) |
| `getAll(keys)` | **batched** multi-get with partial cache hits — the in-loop antidote to N+1 |
| `preloadAll()` | mirror the whole collection into the cache (a small hot set) |
| `saveAndCache(value)` | write-through: persist **and** update the cell |
| `deleteAndEvict(key)` | delete from the backend **and** evict the cell |
| `invalidate(key)` / `evict(key)` / `invalidateAll()` / `clearCache()` | invalidation knobs |
| `purgeExpired()` | drop entries the policy no longer considers fresh (release memory) |
| `repository()` | the underlying uncached `Repository` (queries, cache-bypassing reads) |

---

## Freshness vs capacity

Two different concerns, two different types — on purpose:

- **Freshness** is a `CachePolicy` (`always()`, `ttl(Duration)`, `noCache()`), evaluated per read. It
  is **per-reference overridable** (see below).
- **Capacity** is `CacheOptions.maxSize` (LRU eviction), a property of the **store** — shared by every
  entry, so it is *not* per-reference overridable.

You can't give one reference its own `maxSize` (the store is shared), but you *can* give it its own
freshness. So `maxSize` is a manager knob; TTL is a per-read knob.

> **Memory & GC.** Cached entries are held by **strong** references, so TTL governs *freshness*, not
> memory. Bound memory with `maxSize` (evicted entries become GC-eligible) and/or call `purgeExpired()`
> periodically. (A `SoftReference` mode is intentionally not used — real caches like Guava/Caffeine
> default to size+time eviction over soft refs for the same reasons.)

---

## Per-reference policy (`@RefPolicy`)

The manager owns a default policy per type, but a single reference can override freshness right where
it's declared:

```java
@Data @NoArgsConstructor
public class Guild {
    private UUID id;
    private String name;

    @RefPolicy(ttlSeconds = 180)                       // this ref: 3-minute TTL
    private Ref<UUID, GuildBattleData> battleData;

    @RefPolicy(noCache = true)                         // this ref: always hit the backend
    private Ref<UUID, LiveScoreboard> scoreboard;
}
```

`ttlSeconds > 0` → TTL; `0` → `always()`; `< 0` → inherit the manager default; `noCache = true` → a
true bypass. The override only changes *this* reference's freshness verdict — the cached value stays
shared (one instance for everyone). A stricter reference may trigger a reload that refreshes the entry
for all consumers — never staler, possibly fresher.

> **Works with Lombok `@Data`.** `@RefPolicy` (like core's `@Indexed`) is read from the **field** —
> the deserializer falls back to reading the declared field by reflection when a Lombok-generated setter
> would otherwise hide it. So private fields + `@Data` are fully supported.

---

## Writing & deleting through the cache

The cache-aware write/delete pair keeps the cache and backend consistent — prefer them over
`repository().save/delete`, which touch the backend but leave a stale cache entry:

```java
guilds.saveAndCache(guild).join();     // persist + update the cell (memoized handles see it)
guilds.deleteAndEvict(id).join();      // delete + evict (a tombstone blocks racy in-flight reloads)
```

Both are stamp-ordered: a slower in-flight reload can neither clobber a newer `saveAndCache` nor
resurrect a newer `deleteAndEvict`. On an `OptimisticLockException`, `saveAndCache` auto-evicts the
stale entry so the next read reloads.

---

## Nested references

Nesting falls out for free: **each entity type has its own manager**, with its own policy/TTL. A
`Guild` held in memory does **not** drag its `GuildBattleData` in — it only holds the key.

```java
new GuildManager(storage).preloadAll().join();                    // guilds resident (always())
new CachingManager<>(BATTLE, storage, CachePolicy.ttl(Duration.ofMinutes(3)));  // battle data lazy, 3-min TTL

Guild g = player.getGuild().peek().orElseThrow();                 // memory
g.getBattleData().resolve().thenAccept(opt -> render(opt));       // backend on a cold miss, then TTL'd
```

`preloadAll()` of guilds does not cascade into battle data; cycles are safe (resolution is lazy);
N+1 is opt-in per level (batch with `getAll(...)`).

---

## ⭐ One entity, many databases, many key types

This is the payoff. Because a `Ref` resolves through its type's manager — and a manager can be backed
by **any** `Storage` — a single root entity can fan out across **heterogeneous databases**, each
reference under its **own key type**, and the root neither knows nor cares.

```java
// A root profile in MariaDB, referencing five entities each in a DIFFERENT database & key type.
@Data @NoArgsConstructor @AllArgsConstructor
public class PlayerProfile {
    private UUID uuid;
    private Ref<String, Clan>           clan;      // String key  -> PostgreSQL
    private Ref<Long, Wallet>           wallet;    // Long key    -> MongoDB
    private Ref<Integer, Stats>         stats;     // Integer key -> H2
    private Ref<Settings.Key, Settings> settings;  // record key  -> LocalFile
    private Ref<Session.Id, Session>    session;   // record key  -> InMemory
}

// One manager per type, each on its own backend:
new CachingManager<>(PROFILES, mariadb,  CachePolicy.always());
new CachingManager<>(CLANS,    postgres, CachePolicy.always());
new CachingManager<>(WALLETS,  mongo,    CachePolicy.always());
new CachingManager<>(STATS,    h2,       CachePolicy.always());
new CachingManager<>(SETTINGS, localFile,CachePolicy.always());
new CachingManager<>(SESSIONS, inMemory, CachePolicy.always());

// Load the root, then resolve each reference — every one hits a different database:
PlayerProfile p = profiles.resolve(profileId).join().orElseThrow();
String clanName  = p.getClan().resolve().join().get().getName();       // PostgreSQL
long   balance   = p.getWallet().resolve().join().get().getBalance();  // MongoDB
int    kills     = p.getStats().resolve().join().get().getKills();     // H2
```

The same `Ref`/`CachingManager` machinery handles `UUID`, `String`, `Long`, `Integer`, and even
composite/wrapper `record` keys — the key serializes inside the root's JSON and is recovered on read.
A working, end-to-end version of this (against all six real backends, plus an embedded-only variant
that runs without Docker) lives in
[`MultiBackendRefExampleTest`](src/test/java/br/com/finalcraft/everydatabase/manager/MultiBackendRefExampleTest.java).

> **Why this is nice:** your domain model expresses relationships naturally (`profile.getWallet()`),
> while *where* each piece is stored stays a deployment decision — wallets in Mongo, stats in H2,
> sessions in memory — changeable without touching a line of the entity or the call sites.

---

## What it guarantees (and what it doesn't)

The cache hands out the **same instance** per key for its lifetime — an identity map. Publication is
atomic and stamp-ordered, so concurrent cold misses converge on one instance and an authoritative
`saveAndCache`/`deleteAndEvict` is never clobbered by a slower reload. But the value is shared mutable
state, and freshness is bounded:

| Hazard | Solved by optimistic lock? | What solves it |
|---|:---:|---|
| **Read staleness** (you don't see another process's update) | ❌ | TTL / `invalidate` |
| **Write staleness** (you save a stale copy over a newer one) | ✅ (versioned backends) | `OptimisticLockException` → auto-evict + reload |
| **Mutating a swapped-out value** | ❌ | mutate *through* `saveAndCache`, or single-writer discipline |

Cross-process writes are invisible to a local cache — bound them with a TTL and/or an external
invalidation signal wired to `manager.evict(key)`.

Design rationale, the cell/stamp model and the full hazard analysis live in
[`specs/SPEC_refs_and_managers.md`](../specs/SPEC_refs_and_managers.md).

---

## Non-goals

No eager fetch, no lazy proxies, no hidden I/O, no implicit joins, no entity-graph mapping, no dirty
tracking. Resolution is always explicit (`peek`/`resolve`) and batchable (`getAll`). The cache is
opt-in; freshness is a knob the caller owns.

<div align="center">

Part of [**EveryDatabase**](../README.md) · Made by [Petrus Pradella](https://petrus.dev)

</div>
