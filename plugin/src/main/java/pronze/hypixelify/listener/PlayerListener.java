package pronze.hypixelify.listener;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.screamingsandals.bedwars.Main;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.game.GameStatus;
import org.screamingsandals.bedwars.game.GamePlayer;
import org.screamingsandals.bedwars.lib.ext.pronze.scoreboards.Scoreboard;
import org.screamingsandals.bedwars.lib.ext.pronze.scoreboards.ScoreboardManager;
import org.screamingsandals.bedwars.lib.player.PlayerMapper;
import pronze.hypixelify.SBAHypixelify;
import pronze.hypixelify.api.Permissions;
import pronze.hypixelify.game.PlayerWrapperImpl;
import pronze.hypixelify.utils.Logger;
import pronze.hypixelify.utils.SBAUtil;
import pronze.hypixelify.utils.ScoreboardUtil;
import pronze.hypixelify.utils.ShopUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static pronze.hypixelify.lib.lang.I.i18n;

public class PlayerListener implements Listener {
    private final List<Material> allowedDropItems;
    private final List<Material> generatorDropItems;


    public PlayerListener() {
        allowedDropItems = SBAUtil.parseMaterialFromConfig("allowed-item-drops");
        generatorDropItems = SBAUtil.parseMaterialFromConfig("running-generator-drops");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Logger.trace("Player death event called");
        final var player = e.getEntity();
        final var game = BedwarsAPI.getInstance().getGameOfPlayer(player);
        if (game == null || game.getStatus() != GameStatus.RUNNING) return;
        SBAHypixelify
                .getInstance()
                .getArenaManager()
                .get(game.getName()).ifPresentOrElse(arena -> {

            final var itemArr = new ArrayList<ItemStack>();
            if (SBAHypixelify.getConfigurator().config.getBoolean("respawn-cooldown.enabled", true)) {
                final var sword = Main.isLegacy() ?
                        new ItemStack(Material.valueOf("WOOD_SWORD")) :
                        new ItemStack(Material.WOODEN_SWORD);

                Arrays.stream(player
                        .getInventory()
                        .getContents()
                        .clone())
                        .filter(Objects::nonNull)
                        .forEach(stack -> {
                            final String name = stack.getType().name();
                            var endStr = name.substring(name.contains("_") ? name.indexOf("_") : name.length());
                            switch (endStr) {
                                case "SWORD":
                                    sword.addEnchantments(stack.getEnchantments());
                                    break;
                                case "AXE":
                                    itemArr.add(ShopUtil.checkifUpgraded(stack));
                                    break;
                                case "SHEARS":
                                case "LEGGINGS":
                                case "BOOTS":
                                case "CHESTPLATE":
                                case "HELMET":
                                    itemArr.add(stack);
                                    break;
                            }
                        });

                itemArr.add(sword);
                arena.getPlayerData(player.getUniqueId())
                        .ifPresent(playerData -> playerData.setInventory(itemArr));
            }


            if (SBAHypixelify.getConfigurator().config.getBoolean("give-killer-resources", true)) {
                final var killer = e.getEntity().getKiller();

                if (killer != null && BedwarsAPI.getInstance().isPlayerPlayingAnyGame(killer)
                        && killer.getGameMode() == GameMode.SURVIVAL) {
                    Arrays.stream(player.getInventory().getContents())
                            .filter(Objects::nonNull)
                            .forEach(drop -> {
                                if (generatorDropItems.contains(drop.getType())) {
                                    killer.sendMessage("+" + drop.getAmount() + " " + drop.getType().name());
                                    killer.getInventory().addItem(drop);
                                }
                            });
                }
            }

            final var gVictim = Main.getPlayerGameProfile(player);
            final var victimTeam = Main.getInstance().getGameManager().getGame(game.getName()).get().getTeamOfPlayer(gVictim.player);

            if (SBAHypixelify.getConfigurator().config.getBoolean("respawn-cooldown.enabled", true) &&
                    victimTeam.isAlive() && game.isPlayerInAnyTeam(player) &&
                    game.getTeamOfPlayer(player).isTargetBlockExists()) {

                new BukkitRunnable() {
                    final GamePlayer gamePlayer = gVictim;
                    final Player player = gamePlayer.player;
                    int livingTime = SBAHypixelify.getConfigurator().config.getInt("respawn-cooldown.time", 5);

                    byte buffer = 2;

                    @Override
                    public void run() {
                        if (!BedwarsAPI.getInstance().isPlayerPlayingAnyGame(player)) {
                            this.cancel();
                            return;
                        }

                        //send custom title because we disabled Bedwars from showing any title
                        if (livingTime > 0) {
                            SBAUtil.sendTitle(PlayerMapper.wrapPlayer(player), i18n("respawn-title"),
                                    i18n("respawn-subtitle")
                                            .replace("%time%", String.valueOf(livingTime)),
                                    0, 20, 0);

                            player.sendMessage(i18n("respawn-message")
                                    .replace("%time%", String.valueOf(livingTime)));
                            livingTime--;
                        }


                        if (livingTime == 0) {
                            if (gVictim.isSpectator && buffer > 0) {
                                buffer--;
                            } else {
                                player.sendMessage(i18n("respawned-message"));
                                SBAUtil.sendTitle(PlayerMapper.wrapPlayer(player), i18n("respawned-title"), "",
                                        5, 40, 5);
                                ShopUtil.giveItemToPlayer(itemArr, player,
                                        Main.getInstance().getGameManager().getGame(game.getName()).get().getTeamOfPlayer(gamePlayer.player).getColor());
                                this.cancel();
                            }
                        }
                    }
                }.runTaskTimer(SBAHypixelify.getInstance(), 0L, 20L);
            }
        }, () -> {
            Logger.trace("Event hit null arena");
        });


    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (event.getCurrentItem() == null)
            return;

        if (!(event.getWhoClicked() instanceof Player)) return;

        final var player = (Player) event.getWhoClicked();

        if (!BedwarsAPI.getInstance().isPlayerPlayingAnyGame(player)) return;

        if (SBAHypixelify.getConfigurator().config.getBoolean("disable-armor-inventory-movement", true) &&
                event.getSlotType() == SlotType.ARMOR)
            event.setCancelled(true);

        final var topSlot = event.getView().getTopInventory();
        final var bottomSlot = event.getView().getBottomInventory();
        final var clickedInventory = event.getClickedInventory();
        final var typeName = event.getCurrentItem().getType().name();

        if (clickedInventory == null) return;

        if (clickedInventory.equals(bottomSlot) && SBAHypixelify.getConfigurator().config.getBoolean("block-players-putting-certain-items-onto-chest", true)
                && (topSlot.getType() == InventoryType.CHEST || topSlot.getType() == InventoryType.ENDER_CHEST)
                && bottomSlot.getType() == InventoryType.PLAYER) {
            if (typeName.endsWith("AXE") || typeName.endsWith("SWORD")) {
                event.setResult(Event.Result.DENY);
                player.sendMessage("§c§l" + i18n("cannot-put-item-on-chest"));
            }
        }
    }


    @EventHandler
    public void onItemDrop(PlayerDropItemEvent evt) {
        if (!BedwarsAPI.getInstance().isPlayerPlayingAnyGame(evt.getPlayer())) return;
        if (!SBAHypixelify.getConfigurator().config.getBoolean("block-item-drops", true)) return;

        final var player = evt.getPlayer();
        final var ItemDrop = evt.getItemDrop().getItemStack();
        final var type = ItemDrop.getType();

        if (!allowedDropItems.contains(type) && !type.name().endsWith("WOOL")) {
            evt.setCancelled(true);
            player.getInventory().remove(ItemDrop);
        }
    }


    @EventHandler
    public void itemDamage(PlayerItemDamageEvent e) {
        if (!SBAHypixelify.getConfigurator().config.getBoolean("disable-sword-armor-damage", true)) return;
        var player = e.getPlayer();
        if (!BedwarsAPI.getInstance().isPlayerPlayingAnyGame(player)) return;
        if (Main.getPlayerGameProfile(player).isSpectator) return;

        final String typeName = e.getItem().getType().toString();
        if (typeName.contains("BOOTS")
                || typeName.contains("HELMET")
                || typeName.contains("LEGGINGS")
                || typeName.contains("CHESTPLATE")
                || typeName.contains("SWORD")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent e) {
        final var player = e.getPlayer();
        final var uuid = player.getUniqueId();
        ScoreboardManager
                .getInstance()
                .fromCache(uuid)
                .ifPresent(Scoreboard::destroy);
        ScoreboardUtil.removePlayer(player);
        final var wrappedPlayer =  PlayerMapper.wrapPlayer(player)
                .as(PlayerWrapperImpl.class);
        SBAHypixelify
                .getInstance()
                .getPartyManager()
                .getPartyOf(wrappedPlayer)
                .ifPresent(party -> {
                    party.removePlayer(wrappedPlayer);
                    if (party.getMembers().size() == 1) {
                        SBAHypixelify
                                .getInstance()
                                .getPartyManager()
                                .disband(party.getUUID());
                        return;
                    }
                    if (party.getPartyLeader().equals(wrappedPlayer)) {
                        party
                                .getMembers()
                                .stream()
                                .findAny()
                                .ifPresentOrElse(member -> {
                                    party.setPartyLeader(member);
                                    SBAHypixelify
                                            .getConfigurator()
                                            .getStringList("party.message.promoted-leader")
                                            .stream().map(str -> str.replace("{player}", member.getName()))
                                            .forEach(str -> party.getMembers().forEach(m -> m.getInstance().sendMessage(str)));
                                }, () -> SBAHypixelify.getInstance().getPartyManager()
                                        .disband(party.getUUID()));
                    }
                });
        SBAHypixelify.getInstance().getPlayerWrapperService().unregister(player);
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        final var player = e.getPlayer();
        SBAHypixelify.getInstance().getPlayerWrapperService().register(player);
        if (player.hasPermission(Permissions.UPGRADE.getKey())) {
            if (SBAHypixelify.getInstance().isUpgraded()) {
                Bukkit.getScheduler().runTaskLater(SBAHypixelify.getInstance(), () -> {
                    player.sendMessage("§6[SBAHypixelify]: Plugin has detected a version change, do you want to upgrade internal files?");
                    player.sendMessage("Type /bwaddon upgrade to upgrade file");
                    player.sendMessage("§cif you want to cancel the upgrade files do /bwaddon cancel");
                }, 40L);
            }
        }
    }


}
