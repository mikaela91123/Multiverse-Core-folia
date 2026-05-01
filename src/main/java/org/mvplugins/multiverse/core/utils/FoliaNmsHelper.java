package org.mvplugins.multiverse.core.utils;

import com.dumptruckman.minecraft.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Helper class that uses reflection to access NMS internals for world
 * creation and unloading on Folia servers where the Bukkit API throws
 * {@link UnsupportedOperationException}.
 * <p>
 * Based on the approach used by MoreFoWorld and the Folia PR #63.
 */
public final class FoliaNmsHelper {

    private FoliaNmsHelper() {
    }

    // Cached reflection objects
    private static Class<?> dedicatedServerClass;
    private static Class<?> serverLevelClass;
    private static Class<?> levelStemClass;
    private static Class<?> resourceKeyClass;
    private static Class<?> levelResourceClass;
    private static Class<?> worldGenSettingsClass;
    private static Class<?> levelStorageSourceClass;
    private static Class<?> levelStorageSourceLevelStorageAccessClass;
    private static Class<?> primaryLevelDataClass;
    private static Class<?> worldDimensionsClass;
    private static Class<?> levelDataAndDimensionsClass;
    private static Class<?> registriesClass;
    private static Class<?> levelStemResolverClass; // LevelStem.Resolver or similar
    private static Class<?> savedDataStorageClass;
    private static Class<?> worldInfoClass; // LevelData
    private static Class<?> chunkGeneratorClass;
    private static Class<?> biomeProviderClass; // NMS biome provider

    private static boolean initialized = false;
    private static boolean available = false;

    private static synchronized boolean ensureInitialized() {
        if (initialized) return available;
        initialized = true;
        try {
            // Get the CraftServer class (version-independent with paperweight mojang mappings)
            // On Paper/Folia 1.20.5+, CraftBukkit classes use mojang-mapped names
            // We need to find the actual CraftServer class
            Class<?> craftServerClass = Bukkit.getServer().getClass();

            // Try to find DedicatedServer through CraftServer
            // CraftServer -> MinecraftServer -> DedicatedServer
            Method getServerMethod = findMethod(craftServerClass, "getServer", "server");
            if (getServerMethod == null) {
                Logging.severe("[FoliaNmsHelper] Cannot find CraftServer.getServer()");
                return false;
            }
            Object mcServer = getServerMethod.invoke(Bukkit.getServer());
            if (mcServer == null) {
                Logging.severe("[FoliaNmsHelper] MinecraftServer instance is null");
                return false;
            }

            // Cache the server class hierarchy
            dedicatedServerClass = mcServer.getClass();

            // Find ServerLevel class
            serverLevelClass = findClass(
                    "net.minecraft.server.level.ServerLevel",
                    "net.minecraft.server.level.WorldServer"
            );
            if (serverLevelClass == null) {
                // Try finding it from an existing world
                World overworld = Bukkit.getWorlds().get(0);
                try {
                    Method getHandle = findMethod(overworld.getClass(), "getHandle");
                    if (getHandle != null) {
                        Object nmsWorld = getHandle.invoke(overworld);
                        serverLevelClass = nmsWorld.getClass();
                    }
                } catch (Exception ignored) {
                }
            }

            levelStemClass = findClass(
                    "net.minecraft.world.level.LevelStem",
                    "net.minecraft.world.level.WorldDimension"
            );

            resourceKeyClass = findClass(
                    "net.minecraft.resources.ResourceKey",
                    "net.minecraft.resources.MinecraftKey"
            );

            levelResourceClass = findClass(
                    "net.minecraft.world.level.LevelResource",
                    "net.minecraft.world.level.WorldResource"
            );

            worldGenSettingsClass = findClass(
                    "net.minecraft.world.level.WorldGenSettings",
                    "net.minecraft.world.level.WorldSettings"
            );

            levelStorageSourceClass = findClass(
                    "net.minecraft.world.level.storage.LevelStorageSource",
                    "net.minecraft.world.level.storage.Convertable"
            );

            primaryLevelDataClass = findClass(
                    "net.minecraft.world.level.storage.PrimaryLevelData",
                    "net.minecraft.world.level.storage.WorldData"
            );

            worldDimensionsClass = findClass(
                    "net.minecraft.world.level.dimension.LevelStem",
                    "net.minecraft.world.level.dimension.WorldDimension"
            );

            levelDataAndDimensionsClass = findClass(
                    "net.minecraft.world.level.LevelDataAndDimensions",
                    "net.minecraft.world.level.WorldDataAndDimensions"
            );

            registriesClass = findClass(
                    "net.minecraft.core.RegistryAccess",
                    "net.minecraft.core.IRegistryCustom"
            );

            savedDataStorageClass = findClass(
                    "net.minecraft.world.level.storage SavedDataStorage",
                    "net.minecraft.world.level.storage.WorldPersistentData"
            );

            chunkGeneratorClass = findClass(
                    "net.minecraft.world.level.chunk.ChunkGenerator",
                    "net.minecraft.world.level.chunk.ChunkGenerator"
            );

            available = serverLevelClass != null && resourceKeyClass != null && levelStemClass != null;
            if (available) {
                Logging.info("[FoliaNmsHelper] NMS helper initialized successfully");
            } else {
                Logging.warning("[FoliaNmsHelper] Some NMS classes not found, Folia NMS world loading unavailable");
            }
            return available;
        } catch (Exception e) {
            Logging.severe("[FoliaNmsHelper] Failed to initialize: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Creates a world on Folia using NMS reflection, bypassing the
     * UnsupportedOperationException thrown by CraftServer.createWorld().
     *
     * @param creator The Bukkit WorldCreator specifying world parameters.
     * @return The created World, or null if creation failed.
     */
    public static World createWorldNms(WorldCreator creator) {
        if (!ensureInitialized()) {
            Logging.severe("[FoliaNmsHelper] NMS helper not available, cannot create world");
            return null;
        }

        String name = creator.name();

        // Check if world already exists
        if (Bukkit.getWorld(name) != null) {
            Logging.fine("[FoliaNmsHelper] World already loaded: " + name);
            return Bukkit.getWorld(name);
        }

        try {
            Object mcServer = getMinecraftServer();
            if (mcServer == null) return null;

            // Step 1: Get the dimension key for this world's environment
            Object actualDimension = getActualDimension(creator.environment());
            Object dimensionKey = getDimensionKey(creator);

            // Step 2: Migrate legacy world folder if needed
            migrateLegacyWorld(mcServer, name, actualDimension, dimensionKey);

            // Step 3: Load world data using PaperWorldLoader
            Object loadedWorldData = loadWorldData(mcServer, dimensionKey, name);
            if (loadedWorldData == null) {
                Logging.severe("[FoliaNmsHelper] Failed to load world data for: " + name);
                return null;
            }

            // Step 4: Get or create WorldGenSettings
            Object worldGenSettings = getWorldGenSettings(mcServer, loadedWorldData, creator, actualDimension, dimensionKey);
            if (worldGenSettings == null) {
                Logging.severe("[FoliaNmsHelper] Failed to get WorldGenSettings for: " + name);
                return null;
            }

            // Step 5: Create ServerLevel
            return createServerLevel(mcServer, creator, actualDimension, dimensionKey, loadedWorldData, worldGenSettings);

        } catch (Exception e) {
            Logging.severe("[FoliaNmsHelper] Failed to create world '" + name + "': " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Unloads a world on Folia using NMS reflection.
     *
     * @param world The world to unload.
     * @param save  Whether to save before unloading.
     * @return true if the world was unloaded successfully.
     */
    public static boolean unloadWorldNms(World world, boolean save) {
        if (!ensureInitialized()) {
            Logging.severe("[FoliaNmsHelper] NMS helper not available, cannot unload world");
            return false;
        }

        try {
            Object mcServer = getMinecraftServer();
            if (mcServer == null) return false;

            // Get the ServerLevel (NMS handle)
            Method getHandleMethod = findMethod(world.getClass(), "getHandle");
            if (getHandleMethod == null) {
                Logging.severe("[FoliaNmsHelper] Cannot find getHandle() on world");
                return false;
            }
            Object serverLevel = getHandleMethod.invoke(world);
            if (serverLevel == null) return false;

            // Save the world if requested
            if (save) {
                try {
                    Method saveMethod = findMethod(serverLevelClass, "save", "saveLevel");
                    if (saveMethod != null) {
                        saveMethod.invoke(serverLevel);
                    }
                } catch (Exception e) {
                    Logging.warning("[FoliaNmsHelper] Failed to save world before unload: " + e.getMessage());
                }
            }

            // Call console.unloadLevel(serverLevel) or equivalent
            Method unloadLevelMethod = findMethod(dedicatedServerClass, "unloadLevel", "removeLevel");
            if (unloadLevelMethod != null) {
                unloadLevelMethod.invoke(mcServer, serverLevel);
                Logging.info("[FoliaNmsHelper] Unloaded world: " + world.getName());
                return true;
            }

            // Fallback: try the regionized server approach for Folia
            // On Folia, we need to stop ticking regions and then unload
            return unloadWorldFolia(mcServer, serverLevel, world.getName());

        } catch (Exception e) {
            Logging.severe("[FoliaNmsHelper] Failed to unload world '" + world.getName() + "': " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean unloadWorldFolia(Object mcServer, Object serverLevel, String worldName) {
        try {
            // Try to find and call the Folia-specific unload method
            // RegionizedServer handles world unloading on Folia
            Class<?> regionizedServerClass = findClass(
                    "io.papermc.paper.threadedregions.RegionizedServer"
            );
            if (regionizedServerClass == null) {
                Logging.warning("[FoliaNmsHelper] RegionizedServer class not found");
                return false;
            }

            // Try to get the RegionizedServer instance
            Field instanceField = findField(regionizedServerClass, "INSTANCE", "instance");
            if (instanceField == null) {
                // Try getting it from the server
                Method getRegionizedServer = findMethod(dedicatedServerClass, "getRegionizedServer", "regionizedServer");
                if (getRegionizedServer != null) {
                    Object regionizedServer = getRegionizedServer.invoke(mcServer);
                    if (regionizedServer != null) {
                        // Try to schedule unload on the global tick
                        Method unloadMethod = findMethod(regionizedServerClass, "unloadWorld", "removeWorld");
                        if (unloadMethod != null) {
                            unloadMethod.invoke(regionizedServer, serverLevel);
                            Logging.info("[FoliaNmsHelper] Unloaded world via RegionizedServer: " + worldName);
                            return true;
                        }
                    }
                }
            }

            Logging.warning("[FoliaNmsHelper] Could not find Folia unload method");
            return false;
        } catch (Exception e) {
            Logging.severe("[FoliaNmsHelper] Folia unload failed: " + e.getMessage());
            return false;
        }
    }

    private static Object getMinecraftServer() throws Exception {
        Method getServerMethod = findMethod(Bukkit.getServer().getClass(), "getServer", "server");
        if (getServerMethod == null) return null;
        return getServerMethod.invoke(Bukkit.getServer());
    }

    private static Object getActualDimension(World.Environment environment) throws Exception {
        // Map Bukkit Environment to NMS LevelStem (ResourceKey)
        // OVERWORLD, NETHER, END
        String dimensionName = switch (environment) {
            case NORMAL -> "OVERWORLD";
            case NETHER -> "NETHER";
            case THE_END -> "END";
            default -> throw new IllegalArgumentException("Illegal dimension (" + environment + ")");
        };

        // LevelStem.OVERWORLD, LevelStem.NETHER, LevelStem.END
        Field dimensionField = findField(levelStemClass, dimensionName);
        if (dimensionField == null) {
            // Try lowercase
            dimensionField = findField(levelStemClass, dimensionName.toLowerCase());
        }
        if (dimensionField != null) {
            return dimensionField.get(null);
        }

        // Try creating ResourceKey
        return createResourceKey("dimension", dimensionName.toLowerCase());
    }

    private static Object getDimensionKey(WorldCreator creator) throws Exception {
        // ResourceKey.create(Registries.DIMENSION, ResourceLocation.tryParse(creator.key()))
        // On Paper, the key is derived from the world name
        String keyString = creator.key() != null ? creator.key().toString() : creator.name().toLowerCase();
        return createResourceKey("dimension", keyString);
    }

    private static Object createResourceKey(String registryName, String keyName) throws Exception {
        if (resourceKeyClass == null) return null;

        // Try ResourceKey.create()
        Method createMethod = findMethod(resourceKeyClass, "create");
        if (createMethod == null) {
            createMethod = findMethod(resourceKeyClass, "createKey");
        }

        // Try to find the registry key for dimensions
        Object registryKey = null;
        Class<?> registriesClass = findClass("net.minecraft.core.registries.Registries");
        if (registriesClass != null) {
            Field dimField = findField(registriesClass, "DIMENSION", "LEVEL_STEM");
            if (dimField != null) {
                registryKey = dimField.get(null);
            }
        }

        // Create ResourceLocation for the key name
        Class<?> resourceLocationClass = findClass(
                "net.minecraft.resources.ResourceLocation",
                "net.minecraft.resources.MinecraftKey"
        );
        if (resourceLocationClass == null) return null;

        Object resourceLocation;
        Method tryParseMethod = findMethod(resourceLocationClass, "tryParse");
        if (tryParseMethod != null) {
            resourceLocation = tryParseMethod.invoke(null, keyName);
        } else {
            Constructor<?> constructor = resourceLocationClass.getConstructor(String.class);
            resourceLocation = constructor.newInstance(keyName);
        }

        if (createMethod != null && registryKey != null && resourceLocation != null) {
            return createMethod.invoke(null, registryKey, resourceLocation);
        }

        return null;
    }

    private static void migrateLegacyWorld(Object mcServer, String name, Object actualDimension, Object dimensionKey) throws Exception {
        // Try WorldFolderMigration.migrateApiWorld()
        Class<?> migrationClass = findClass(
                "io.papermc.paper.world.WorldFolderMigration"
        );
        if (migrationClass != null) {
            Method migrateMethod = findMethod(migrationClass, "migrateApiWorld");
            if (migrateMethod != null) {
                try {
                    Object storageSource = getFieldValue(mcServer, "storageSource", "storageSource");
                    Object registryAccess = getFieldValue(mcServer, "registryAccess", "registryAccess");
                    migrateMethod.invoke(null, storageSource, registryAccess, name, actualDimension, dimensionKey);
                } catch (Exception e) {
                    Logging.fine("[FoliaNmsHelper] Migration skipped or not needed: " + e.getMessage());
                }
            }
        }
    }

    private static Object loadWorldData(Object mcServer, Object dimensionKey, String name) throws Exception {
        // PaperWorldLoader.loadWorldData(console, dimensionKey, name)
        Class<?> paperWorldLoaderClass = findClass(
                "io.papermc.paper.world.PaperWorldLoader"
        );
        if (paperWorldLoaderClass == null) {
            Logging.warning("[FoliaNmsHelper] PaperWorldLoader class not found");
            return null;
        }

        Method loadMethod = findMethod(paperWorldLoaderClass, "loadWorldData");
        if (loadMethod == null) {
            Logging.warning("[FoliaNmsHelper] PaperWorldLoader.loadWorldData method not found");
            return null;
        }

        return loadMethod.invoke(null, mcServer, dimensionKey, name);
    }

    private static Object getWorldGenSettings(Object mcServer, Object loadedWorldData, WorldCreator creator, Object actualDimension, Object dimensionKey) throws Exception {
        // Get WorldGenSettings from loaded data or create new one
        Object worldGenSettings = null;

        // Try getting from loaded world data
        if (loadedWorldData != null) {
            worldGenSettings = getFieldValue(loadedWorldData, "worldGenSettings", "worldGenSettings");
        }

        if (worldGenSettings != null) {
            return worldGenSettings;
        }

        // If no existing world gen settings, we need to create them
        // This happens for new worlds
        Object registryAccess = getFieldValue(mcServer, "registryAccess", "registryAccess");
        if (registryAccess == null) {
            Method getMethod = findMethod(mcServer.getClass(), "registryAccess", "getRegistryAccess");
            if (getMethod != null) {
                registryAccess = getMethod.invoke(mcServer);
            }
        }

        // Try to get LevelStem from registries
        Object levelStemRegistry = null;
        if (registryAccess != null) {
            Class<?> registriesClass = findClass("net.minecraft.core.registries.Registries");
            if (registriesClass != null) {
                Field levelStemField = findField(registriesClass, "LEVEL_STEM", "DIMENSION");
                if (levelStemField != null) {
                    Object levelStemKey = levelStemField.get(null);
                    Method lookupMethod = findMethod(registryAccess.getClass(), "lookup", "registryOrThrow");
                    if (lookupMethod != null) {
                        Object registry = lookupMethod.invoke(registryAccess, levelStemKey);
                        if (registry != null) {
                            Method getValueMethod = findMethod(registry.getClass(), "getValue", "get");
                            if (getValueMethod != null) {
                                levelStemRegistry = getValueMethod.invoke(registry, actualDimension);
                            }
                        }
                    }
                }
            }
        }

        // Create WorldOptions and WorldDimensions
        if (levelStemRegistry == null) {
            Logging.severe("[FoliaNmsHelper] Cannot find level stem registry");
            return null;
        }

        // Build WorldOptions
        Class<?> worldOptionsClass = findClass("net.minecraft.world.level.WorldOptions");
        Object worldOptions = null;
        if (worldOptionsClass != null) {
            Constructor<?> constructor = worldOptionsClass.getConstructor(
                    long.class, boolean.class, boolean.class
            );
            worldOptions = constructor.newInstance(
                    creator.seed() != 0L ? creator.seed() : System.currentTimeMillis(),
                    creator.generateStructures(),
                    creator.hardcore()
            );
        }

        // Build WorldDimensions using the existing registry
        Class<?> dedicatedServerPropertiesClass = findClass(
                "net.minecraft.server.dedicated.DedicatedServerProperties"
        );
        Class<?> worldDimensionsClass2 = findClass(
                "net.minecraft.world.level.dimension.LevelStem"
        );

        // Try to bake dimensions
        Method bakeMethod = findMethod(levelStemClass, "bake", "bakeDimensions");
        Object registryAccess2 = getFieldValue(mcServer, "registryAccess", "registryAccess");

        if (bakeMethod != null && registryAccess2 != null && worldOptions != null) {
            return bakeMethod.invoke(null, registryAccess2, worldOptions);
        }

        return null;
    }

    private static World createServerLevel(Object mcServer, WorldCreator creator, Object actualDimension, Object dimensionKey, Object loadedWorldData, Object worldGenSettings) throws Exception {
        // Get the configured stem for this dimension
        Object dimensions = null;
        if (worldGenSettings != null) {
            dimensions = getFieldValue(worldGenSettings, "dimensions", "dimensions");
        }

        Object configuredStem = null;
        if (dimensions != null) {
            Method getValueMethod = findMethod(dimensions.getClass(), "getValue", "get");
            if (getValueMethod != null) {
                configuredStem = getValueMethod.invoke(dimensions, actualDimension);
            }
        }

        // Fallback to registry lookup
        if (configuredStem == null) {
            Object registryAccess = getFieldValue(mcServer, "registryAccess", "registryAccess");
            if (registryAccess != null) {
                configuredStem = lookupLevelStem(registryAccess, actualDimension);
            }
        }

        if (configuredStem == null) {
            Logging.severe("[FoliaNmsHelper] Missing configured level stem for dimension");
            return null;
        }

        // Get type from stem
        Object stemType = getFieldValue(configuredStem, "type", "type");
        if (stemType == null) {
            stemType = getFieldValue(configuredStem, "generator", "generator");
        }

        // Get chunk generator from stem
        Object chunkGenerator = getFieldValue(configuredStem, "generator", "generator");
        if (chunkGenerator == null && stemType != null) {
            chunkGenerator = getFieldValue(stemType, "generator", "generator");
        }

        // Get PrimaryLevelData
        Object primaryLevelData = null;
        if (loadedWorldData != null) {
            primaryLevelData = getFieldValue(loadedWorldData, "primaryLevelData", "primaryLevelData");
        }
        if (primaryLevelData == null) {
            // Try getting it from the server
            primaryLevelData = getFieldValue(mcServer, "worldData", "worldData");
        }

        // Get LevelStorageSource.LevelStorageAccess
        Object storageSource = getFieldValue(mcServer, "storageSource", "storageSource");

        // Create SavedDataStorage for the dimension
        Object savedDataStorage = null;
        if (storageSource != null) {
            Method createAccessMethod = findMethod(storageSource.getClass(), "createAccess", "createAccess");
            if (createAccessMethod != null) {
                Object levelAccess = createAccessMethod.invoke(storageSource, dimensionKey);
                if (levelAccess != null) {
                    // Create SavedDataStorage
                    Class<?> savedDataStorageClass2 = findClass(
                            "net.minecraft.world.level.storage.SavedDataStorage"
                    );
                    if (savedDataStorageClass2 != null) {
                        Constructor<?> constructor = savedDataStorageClass2.getConstructor(
                                findClass("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess"),
                                findClass("net.minecraft.server.DataFixer")
                        );
                        if (constructor != null) {
                            Object fixer = getFieldValue(mcServer, "fixerUpper", "fixerUpper");
                            savedDataStorage = constructor.newInstance(levelAccess, fixer);
                        }
                    }
                }
            }
        }

        // Get biome zoom seed
        long biomeZoomSeed = 0L;
        if (worldGenSettings != null) {
            Object options = getFieldValue(worldGenSettings, "options", "options");
            if (options != null) {
                Object seed = getFieldValue(options, "seed", "seed");
                if (seed instanceof Long) {
                    biomeZoomSeed = (Long) seed;
                }
            }
        }
        try {
            Class<?> biomeManagerClass = findClass("net.minecraft.world.level.biome.BiomeManager");
            if (biomeManagerClass != null) {
                Method obfuscateSeedMethod = findMethod(biomeManagerClass, "obfuscateSeed");
                if (obfuscateSeedMethod != null) {
                    biomeZoomSeed = (Long) obfuscateSeedMethod.invoke(null, biomeZoomSeed);
                }
            }
        } catch (Exception ignored) {
        }

        // Create spawn list based on environment
        boolean isNormal = creator.environment() == World.Environment.NORMAL;

        // Build ServerLevel constructor arguments
        Object executor = getFieldValue(mcServer, "executor", "executor");
        Object primaryLevelDataObj = primaryLevelData;

        // Try to construct ServerLevel
        Constructor<?> serverLevelConstructor = findConstructor(serverLevelClass,
                dedicatedServerClass,          // server
                findClass("java.util.concurrent.Executor"), // executor
                findClass("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess"), // levelStorageAccess
                primaryLevelDataClass,          // primaryLevelData
                resourceKeyClass,               // resourceKey
                levelStemClass,                 // levelStem
                findClass("net.minecraft.world.level.WorldInfo"), // worldInfo / LevelData
                findClass("net.minecraft.world.level.chunk.ChunkGenerator"), // chunkGenerator
                findClass("net.minecraft.world.level.biome.BiomeProvider"), // biomeProvider
                long.class,                     // biomeZoomSeed
                List.class,                     // customSpawners
                boolean.class,                  // isDebug
                long.class,                     // seed
                findClass("net.minecraft.world.level.WorldOptions"), // worldOptions
                int.class                       // viewDistance or similar
        );

        if (serverLevelConstructor == null) {
            // Try a simpler approach - use the method that CraftServer uses internally
            // but bypass the UnsupportedOperationException check
            return createWorldViaCraftServerInternal(mcServer, creator, configuredStem, dimensionKey, loadedWorldData, worldGenSettings);
        }

        // Create the ServerLevel
        Object serverLevel = serverLevelConstructor.newInstance(
                mcServer,
                executor,
                storageSource,
                primaryLevelDataObj,
                dimensionKey,
                configuredStem,
                primaryLevelDataObj,
                chunkGenerator,
                null, // biomeProvider - let it be derived from chunkGenerator
                biomeZoomSeed,
                isNormal ? List.of() : List.of(), // custom spawners
                false, // isDebug
                creator.seed(),
                null, // worldOptions
                10    // viewDistance
        );

        // Add level to server
        addLevelToServer(mcServer, serverLevel);

        // Init world
        initWorld(mcServer, serverLevel);

        // Return the Bukkit World
        Method getWorldMethod = findMethod(serverLevelClass, "getWorld");
        if (getWorldMethod != null) {
            return (World) getWorldMethod.invoke(serverLevel);
        }

        return null;
    }

    private static World createWorldViaCraftServerInternal(Object mcServer, WorldCreator creator, Object configuredStem, Object dimensionKey, Object loadedWorldData, Object worldGenSettings) throws Exception {
        // This approach tries to call the internal CraftServer.createWorld() logic
        // but bypasses the Folia UnsupportedOperationException check by using
        // the addLevel/initWorld methods directly.

        // First, let's try to call the full createWorld chain from CraftServer
        // but skip the UnsupportedOperationException by calling the NMS methods directly

        // Use console.addLevel() + console.initWorld() pattern from MoreFoWorld

        // Get the server's world data
        Object primaryLevelData = getFieldValue(mcServer, "worldData", "worldData");
        Object storageSource = getFieldValue(mcServer, "storageSource", "storageSource");
        Object executor = getFieldValue(mcServer, "executor", "executor");

        // Try to find the ServerLevel constructor through an existing world
        World existingWorld = Bukkit.getWorlds().get(0);
        Method getHandleMethod = findMethod(existingWorld.getClass(), "getHandle");
        Object existingServerLevel = getHandleMethod.invoke(existingWorld);

        // Try to find addLevel method
        Method addLevelMethod = findMethod(dedicatedServerClass, "addLevel", "loadLevel");
        Method initWorldMethod = findMethod(dedicatedServerClass, "initWorld", "initWorld");

        if (addLevelMethod == null || initWorldMethod == null) {
            Logging.severe("[FoliaNmsHelper] Cannot find addLevel/initWorld methods on DedicatedServer");
            return null;
        }

        // We need to construct a ServerLevel. Try using the same constructor
        // that the existing world used.
        // Let's try a different approach: use the LevelStem from the registry
        // and call MinecraftServer.createLevel() if it exists

        Method createLevelMethod = findMethod(dedicatedServerClass, "createLevel");
        if (createLevelMethod != null) {
            // This would be the ideal path if Folia adds this method
            Object serverLevel = createLevelMethod.invoke(mcServer, configuredStem, dimensionKey, loadedWorldData);
            if (serverLevel != null) {
                Method getWorldMethod = findMethod(serverLevelClass, "getWorld");
                if (getWorldMethod != null) {
                    return (World) getWorldMethod.invoke(serverLevel);
                }
            }
        }

        Logging.severe("[FoliaNmsHelper] No viable method found to create world on Folia");
        return null;
    }

    private static void addLevelToServer(Object mcServer, Object serverLevel) throws Exception {
        Method addLevelMethod = findMethod(dedicatedServerClass, "addLevel", "loadLevel");
        if (addLevelMethod != null) {
            addLevelMethod.invoke(mcServer, serverLevel);
        }
    }

    private static void initWorld(Object mcServer, Object serverLevel) throws Exception {
        Method initWorldMethod = findMethod(dedicatedServerClass, "initWorld", "initWorld");
        if (initWorldMethod != null) {
            initWorldMethod.invoke(mcServer, serverLevel);
        }

        // Also call prepareLevels
        Method prepareLevelMethod = findMethod(dedicatedServerClass, "prepareLevel", "prepareLevels");
        if (prepareLevelMethod != null) {
            prepareLevelMethod.invoke(mcServer, serverLevel);
        } else {
            // Try on ServerLevel directly
            Method prepareMethod = findMethod(serverLevelClass, "prepareLevel", "prepare");
            if (prepareMethod != null) {
                prepareMethod.invoke(serverLevel);
            }
        }
    }

    private static Object lookupLevelStem(Object registryAccess, Object actualDimension) throws Exception {
        Class<?> registriesClass = findClass("net.minecraft.core.registries.Registries");
        if (registriesClass == null) return null;

        Field levelStemField = findField(registriesClass, "LEVEL_STEM", "DIMENSION");
        if (levelStemField == null) return null;

        Object levelStemKey = levelStemField.get(null);
        Method lookupMethod = findMethod(registryAccess.getClass(), "lookup", "registryOrThrow", "registry");
        if (lookupMethod == null) return null;

        Object registry = lookupMethod.invoke(registryAccess, levelStemKey);
        if (registry == null) return null;

        Method getValueMethod = findMethod(registry.getClass(), "getValue", "get");
        if (getValueMethod == null) return null;

        return getValueMethod.invoke(registry, actualDimension);
    }

    // --- Utility methods ---

    private static Class<?> findClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String... names) {
        if (clazz == null) return null;
        for (String name : names) {
            try {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                // Try superclass
                Method m = findMethod(clazz.getSuperclass(), name);
                if (m != null) return m;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String... names) {
        if (clazz == null) return null;
        for (String name : names) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            // Try superclass
            Field f = findField(clazz.getSuperclass(), name);
            if (f != null) return f;
        }
        return null;
    }

    private static Object getFieldValue(Object obj, String... names) throws Exception {
        Class<?> clazz = obj.getClass();
        for (String name : names) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                // Try superclass
                Class<?> superClass = clazz.getSuperclass();
                while (superClass != null) {
                    try {
                        Field f = superClass.getDeclaredField(name);
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (NoSuchFieldException ignored2) {
                    }
                    superClass = superClass.getSuperclass();
                }
            }
        }
        return null;
    }

    private static Constructor<?> findConstructor(Class<?> clazz, Class<?>... paramTypes) {
        if (clazz == null) return null;
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException ignored) {
        }
        // Try all constructors and find one with matching param count
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterCount() == paramTypes.length) {
                c.setAccessible(true);
                return c;
            }
        }
        return null;
    }
}
