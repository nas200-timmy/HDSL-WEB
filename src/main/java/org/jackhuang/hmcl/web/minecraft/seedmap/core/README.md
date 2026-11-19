# Seed-map core

Zero-dependency Java port of the biome and structure maths from
[cubiomes](https://github.com/Cubitect/cubiomes) (MIT). The package can be
copied out of the panel and compiled with nothing but the JDK.

## Supported versions

- 1.18.2, 1.20.6, 1.21.1, 1.21.3 (multi-noise)
- Legacy layer stack (1.12.2, 1.14.4, 1.16.5) is scaffolded but not implemented
  yet; calling it throws `UnsupportedOperationException`.

## Independent use

```java
import org.jackhuang.hmcl.web.minecraft.seedmap.core.*;

SeedMap map = SeedMap.of(Version.V1_21_3, 12345L);
Biome biome = map.biomeAt(100, 200);
List<Structure> villages = map.structures(StructureType.VILLAGE, -512, -512, 512, 512);
List<BlockPos> strongholds = map.strongholds(3);
```

Compile the whole `core/` directory together with the generated tables in
`src/main/resources/seedmap/` on the class path. No panel classes, web
framework, JSON library or logging implementation is referenced.

## Panel integration

`adapter/SeedMapBackend` turns these results into the panel's existing preview
images and reports. The detailed map still uses the real-server backend; the
biome overview uses this core and therefore never starts a Minecraft server.

## Verification

`SeedMapTest` covers deterministic biome lookups, area mapping, village and
stronghold placement and slime-chunk maths. The L1 differential test against
the C reference is the next milestone.
