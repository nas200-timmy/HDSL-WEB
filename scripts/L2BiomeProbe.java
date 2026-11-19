package mcp;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.RegistryLayer;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * L2 reference probe. Loads the vanilla datapack registries, then answers
 * biome queries from the game's own MultiNoiseBiomeSource. Test utility only;
 * never shipped in the panel.
 */
public final class L2BiomeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: L2BiomeProbe <seed>");
            System.exit(1);
        }
        long seed = Long.parseLong(args[0]);
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        var repository = ServerPacksSource.createVanillaTrustedRepository();
        repository.reload();
        repository.setSelected(repository.getAvailablePacks().stream().map(net.minecraft.server.packs.repository.Pack::getId).toList());
        try (CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected())) {
            LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
            var tags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
            RegistryAccess.Frozen worldgenAccess = layers.getAccessForLoading(RegistryLayer.WORLDGEN);
            List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(worldgenAccess, tags);
            var needed = RegistryDataLoader.WORLDGEN_REGISTRIES.stream()
                    .filter(data -> data.key().equals(Registries.NOISE_SETTINGS)
                            || data.key().equals(Registries.NOISE)
                            || data.key().equals(Registries.BIOME)
                            || data.key().equals(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                            || data.key().equals(Registries.DENSITY_FUNCTION)
                            || data.key().equals(Registries.CONFIGURED_CARVER)
                            || data.key().equals(Registries.CONFIGURED_FEATURE)
                            || data.key().equals(Registries.PLACED_FEATURE)
                            || data.key().equals(Registries.STRUCTURE)
                            || data.key().equals(Registries.STRUCTURE_SET)
                            || data.key().equals(Registries.PROCESSOR_LIST)
                            || data.key().equals(Registries.TEMPLATE_POOL)
                            || data.key().equals(Registries.WORLD_PRESET)
                            || data.key().equals(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                            || data.key().equals(Registries.DIMENSION_TYPE))
                    .toList();
            RegistryAccess.Frozen registries = RegistryDataLoader.load(resources, lookups, needed);

            var preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
            RandomState randomState = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed);
            Climate.Sampler sampler = randomState.sampler();

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 3) {
                    continue;
                }
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                Holder<Biome> biome = biomeSource.getNoiseBiome(x, y, z, sampler);
                String key = biome.unwrapKey().map(k -> k.location().toString()).orElse("unknown");
                System.out.println(key);
            }
        }
    }
}
