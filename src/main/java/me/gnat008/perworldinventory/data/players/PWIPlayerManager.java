/*
 * Copyright (C) 2014-2015  Erufael
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.gnat008.perworldinventory.data.players;

import me.gnat008.perworldinventory.PerWorldInventory;
import me.gnat008.perworldinventory.config.defaults.ConfigValues;
import me.gnat008.perworldinventory.data.DataSerializer;
import me.gnat008.perworldinventory.groups.Group;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * This class is used to manage cached players.
 * Players are meant to be added when data needs to be saved, and removed
 * when the data has been saved to the database, whether it be MySQL or
 * flat files.
 */
public class PWIPlayerManager {

    private PerWorldInventory plugin;
    private int taskID;

    private Map<Group, Set<PWIPlayer>> playerCache = new LinkedHashMap<>();

    public PWIPlayerManager(PerWorldInventory plugin) {
        this.plugin = plugin;

        this.taskID = scheduleRepeatingTask();
    }

    /**
     * Called when the plugin is disabled.
     * <p>
     * This method immediately saves any player to the database if they
     * have not already been saved.
     */
    public void onDisable() {
        Bukkit.getScheduler().cancelTask(taskID);

        for (Group group : playerCache.keySet()) {
            for (PWIPlayer player : playerCache.get(group)) {
                if (player.isSaved()) {
                    player.setSaved(true);
                    plugin.getSerializer().saveToDatabase(
                            group,
                            ConfigValues.SEPARATE_GAMEMODE_INVENTORIES.getBoolean() ? player.getGamemode() : GameMode.SURVIVAL,
                            player
                    );
                }
            }
        }

        playerCache.clear();
    }

    /**
     * Add a new player to the cache.
     * <p>
     * Players will be tied to the group they were in. This allows us to have
     * multiple PWIPlayers cached at the same time in case they rapidely change
     * gamemodes or worlds. We can grab data directly from this cache in case
     * they haven't been saved to the database yet.
     *
     * @param player The Player to add
     * @param group The Group the player is in
     */
    public void addPlayer(Player player, Group group) {
        Set<PWIPlayer> players = playerCache.get(group);
        if (players == null) {
            players = new HashSet<>();

            players.add(new PWIPlayer(player, group));
            playerCache.put(group, players);
        } else {
            Iterator<PWIPlayer> itr = players.iterator();
            PWIPlayer pwiPlayer;
            while (itr.hasNext()) {
                pwiPlayer = itr.next();

                if (player.getGameMode() == pwiPlayer.getGamemode() ||
                        !ConfigValues.SEPARATE_GAMEMODE_INVENTORIES.getBoolean()) {
                    updateCache(player, pwiPlayer);
                }
            }
        }
    }

    /**
     * Removes a player from the cache.
     * <p>
     * This method will only remove a player with the given GameMode, leaving
     * any other instances of the same player alone.
     *
     * @param group The {@link me.gnat008.perworldinventory.groups.Group} to remove
     * @param gameMode The {@link org.bukkit.GameMode} to remove
     */
    public void removePlayer(Group group, GameMode gameMode) {
        if (playerCache.containsKey(group)) {
            Set<PWIPlayer> players = playerCache.get(group);
            Iterator<PWIPlayer> itr = players.iterator();
            while (itr.hasNext()) {
                if (itr.next().getGamemode() == gameMode) {
                    itr.remove();
                }
            }
        }
    }

    /**
     * Get a PWI player from a UUID.
     * <p>
     * This method will return null if no player is found, or if they have not been
     * saved with the Group given.
     *
     * @param group The Group to grab data from
     * @param gameMode The GameMode to get the data for
     * @return The PWIPlayer
     */
    public PWIPlayer getPlayer(Group group, GameMode gameMode) {
        if (playerCache.containsKey(group)) {
            Set<PWIPlayer> players = playerCache.get(group);
            for (PWIPlayer player : players) {
                if (player.getGamemode() == gameMode ||
                        !ConfigValues.SEPARATE_GAMEMODE_INVENTORIES.getBoolean())
                    return player;
            }
        }

        return null;
    }

    /**
     * Starts a synchronized repeating task to iterate through all PWIPlayers in the player
     * cache. If the player has not yet been saved to a database, they will be saved.
     * <p>
     * Additionally, if a player is still in the cache, but they have already been saved,
     * remove them from the cache.
     * <p>
     * By default, this task will execute once every 5 minutes. This will likely be
     * configurable in the future.
     *
     * @return The task ID number
     */
    private int scheduleRepeatingTask() {
        return Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            @Override
            public void run() {
                for (final Group group : playerCache.keySet()) {
                    Set<PWIPlayer> players = playerCache.get(group);
                    for (final PWIPlayer player : players) {
                        if (player.isSaved()) {
                            player.setSaved(true);
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                                @Override
                                public void run() {
                                    plugin.getSerializer().saveToDatabase(group, player.getGamemode(), player);
                                }
                            });
                        } else {
                            removePlayer(group, player.getGamemode());
                        }
                    }
                }
            }
        }, 6000L, 6000L);
    }

    /**
     * Updates all the values of a player in the cache.
     *
     * @param newData The current snapshot of the Player
     * @param currentPlayer The PWIPlayer currently in the cache
     */
    private void updateCache(Player newData, PWIPlayer currentPlayer) {
        currentPlayer.setSaved(false);

        currentPlayer.setArmor(newData.getInventory().getArmorContents());
        currentPlayer.setEnderChest(newData.getEnderChest().getContents());
        currentPlayer.setInventory(newData.getInventory().getContents());

        currentPlayer.setCanFly(newData.getAllowFlight());
        currentPlayer.setDisplayName(newData.getDisplayName());
        currentPlayer.setExhaustion(newData.getExhaustion());
        currentPlayer.setExperience(newData.getTotalExperience());
        currentPlayer.setFlying(newData.isFlying());
        currentPlayer.setFoodLevel(newData.getFoodLevel());
        currentPlayer.setHealth(newData.getHealth());
        currentPlayer.setGamemode(newData.getGameMode());
        currentPlayer.setLevel(newData.getLevel());
        currentPlayer.setSaturationLevel(newData.getSaturation());
        currentPlayer.setPotionEffects(newData.getActivePotionEffects());

        if (PerWorldInventory.getInstance().getEconomy() != null) {
            currentPlayer.setBankBalance(PerWorldInventory.getInstance().getEconomy().bankBalance(newData.getName()).balance);
            currentPlayer.setBalance(PerWorldInventory.getInstance().getEconomy().getBalance(newData));
        }
    }
}
