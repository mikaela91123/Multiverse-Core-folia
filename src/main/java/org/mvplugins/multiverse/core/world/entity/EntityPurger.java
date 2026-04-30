package org.mvplugins.multiverse.core.world.entity;

import jakarta.inject.Inject;
import org.bukkit.entity.Entity;
import org.bukkit.entity.SpawnCategory;
import org.jvnet.hk2.annotations.Service;
import org.mvplugins.multiverse.core.MultiverseCore;
import org.mvplugins.multiverse.core.utils.FoliaCompat;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public final class EntityPurger {

    private final MultiverseCore plugin;

    @Inject
    public EntityPurger(MultiverseCore plugin) {
        this.plugin = plugin;
    }

    public int purgeEntities(LoadedMultiverseWorld world) {
        AtomicInteger purgeCount = new AtomicInteger(0);
        world.getBukkitWorld().peek(bukkitWorld -> {
            for (Entity entity : bukkitWorld.getEntities()) {
                if (!world.getEntitySpawnConfig().shouldAllowSpawn(entity)) {
                    removeEntity(entity);
                    purgeCount.incrementAndGet();
                }
            }
        });
        return purgeCount.get();
    }

    public int purgeEntities(LoadedMultiverseWorld world, SpawnCategory spawnCategory) {
        AtomicInteger purgeCount = new AtomicInteger(0);
        world.getBukkitWorld().peek(bukkitWorld -> {
            for (Entity entity : bukkitWorld.getEntities()) {
                if (entity.getSpawnCategory() == spawnCategory) {
                    removeEntity(entity);
                    purgeCount.incrementAndGet();
                }
            }
        });
        return purgeCount.get();
    }

    public int purgeEntities(LoadedMultiverseWorld world, SpawnCategory... spawnCategories) {
        Set<SpawnCategory> spawnCategoriesSet = Set.of(spawnCategories);
        AtomicInteger purgeCount = new AtomicInteger(0);
        world.getBukkitWorld().peek(bukkitWorld -> {
            for (Entity entity : bukkitWorld.getEntities()) {
                if (spawnCategoriesSet.contains(entity.getSpawnCategory())) {
                    removeEntity(entity);
                    purgeCount.incrementAndGet();
                }
            }
        });
        return purgeCount.get();
    }

    public int purgeAllEntities(LoadedMultiverseWorld world) {
        AtomicInteger purgeCount = new AtomicInteger(0);
        world.getBukkitWorld().peek(bukkitWorld -> {
            for (Entity entity : bukkitWorld.getEntities()) {
                removeEntity(entity);
                purgeCount.incrementAndGet();
            }
        });
        return purgeCount.get();
    }

    private void removeEntity(Entity entity) {
        if (FoliaCompat.isFolia()) {
            FoliaCompat.runOnEntityRegion(plugin, entity, entity::remove);
        } else {
            entity.remove();
        }
    }
}
