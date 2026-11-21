/*
 * L1 reference query tool. Build against cubiomes, then feed it version, seed
 * and coordinates on stdin; it prints the biome id at each point.
 *
 * This file is a test utility only and is never shipped in the panel jar.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "generator.h"
#include "biomes.h"

static int versionFromString(const char *s)
{
    if (strcmp(s, "1.12.2") == 0 || strcmp(s, "1.12") == 0) return MC_1_12_2;
    if (strcmp(s, "1.14.4") == 0 || strcmp(s, "1.14") == 0) return MC_1_14_4;
    if (strcmp(s, "1.16.5") == 0 || strcmp(s, "1.16") == 0) return MC_1_16_5;
    if (strcmp(s, "1.18.2") == 0 || strcmp(s, "1.18") == 0) return MC_1_18_2;
    if (strcmp(s, "1.20.6") == 0 || strcmp(s, "1.20") == 0) return MC_1_20_6;
    if (strcmp(s, "1.21.1") == 0) return MC_1_21_1;
    if (strcmp(s, "1.21.3") == 0 || strcmp(s, "1.21.4") == 0 || strcmp(s, "1.21") == 0) return MC_1_21_WD;
    return MC_UNDEF;
}

int main(int argc, char **argv)
{
    if (argc < 3)
    {
        fprintf(stderr, "usage: %s <version> <seed> [x y z ...]\n", argv[0]);
        return 1;
    }
    int mc = versionFromString(argv[1]);
    if (mc == MC_UNDEF)
    {
        fprintf(stderr, "unsupported version %s\n", argv[1]);
        return 1;
    }
    uint64_t seed = strtoull(argv[2], NULL, 10);

    Generator g;
    setupGenerator(&g, mc, 0);
    applySeed(&g, DIM_OVERWORLD, seed);

    if (argc >= 6)
    {
        int x = atoi(argv[3]);
        int y = atoi(argv[4]);
        int z = atoi(argv[5]);
        if (argc >= 7 && strcmp(argv[6], "climate") == 0)
        {
            int64_t np[6];
            sampleBiomeNoise(&g.bn, np, x, y, z, NULL, SAMPLE_NO_BIOME);
            printf("%lld %lld %lld %lld %lld %lld\n",
                (long long)np[0], (long long)np[1], (long long)np[2],
                (long long)np[3], (long long)np[4], (long long)np[5]);
            return 0;
        }
        if (argc >= 7 && strncmp(argv[6], "layer", 5) == 0)
        {
            int scale = atoi(argv[6] + 5);
            const Layer *layer = getLayerForScale(&g, scale);
            size_t siz = getMinLayerCacheSize(layer, 1, 1);
            int *out = calloc(siz, sizeof(int));
            genArea(layer, out, x, z, 1, 1);
            printf("%d\n", out[0]);
            free(out);
            return 0;
        }
        if (argc >= 7 && strcmp(argv[6], "continent") == 0)
        {
            int out[1];
            genArea(&g.ls.layers[L_CONTINENT_4096], out, x, z, 1, 1);
            printf("%d\n", out[0]);
            return 0;
        }
        if (argc >= 7 && strcmp(argv[6], "biome256") == 0)
        {
            size_t siz = getMinLayerCacheSize(&g.ls.layers[L_BIOME_256], 1, 1);
            int *out = calloc(siz, sizeof(int));
            genArea(&g.ls.layers[L_BIOME_256], out, x, z, 1, 1);
            printf("%d\n", out[0]);
            free(out);
            return 0;
        }
        if (argc >= 7 && strcmp(argv[6], "deepocean256") == 0)
        {
            size_t siz = getMinLayerCacheSize(&g.ls.layers[L_DEEP_OCEAN_256], 1, 1);
            int *out = calloc(siz, sizeof(int));
            genArea(&g.ls.layers[L_DEEP_OCEAN_256], out, x, z, 1, 1);
            printf("%d\n", out[0]);
            free(out);
            return 0;
        }
        if (argc >= 7 && strcmp(argv[6], "mushroom256") == 0)
        {
            size_t siz = getMinLayerCacheSize(&g.ls.layers[L_MUSHROOM_256], 1, 1);
            int *out = calloc(siz, sizeof(int));
            genArea(&g.ls.layers[L_MUSHROOM_256], out, x, z, 1, 1);
            printf("%d\n", out[0]);
            free(out);
            return 0;
        }
        if (argc >= 7 && strcmp(argv[6], "land256") == 0)
        {
            size_t siz = getMinLayerCacheSize(&g.ls.layers[L_LAND_256], 1, 1);
            int *out = calloc(siz, sizeof(int));
            genArea(&g.ls.layers[L_LAND_256], out, x, z, 1, 1);
            printf("%d\n", out[0]);
            free(out);
            return 0;
        }
        printf("%d\n", getBiomeAt(&g, 4, x, y, z));
        return 0;
    }

    char line[256];
    while (fgets(line, sizeof(line), stdin))
    {
        int x, y, z;
        if (sscanf(line, "%d %d %d", &x, &y, &z) != 3)
            continue;
        printf("%d\n", getBiomeAt(&g, 4, x, y, z));
        fflush(stdout);
    }
    return 0;
}
