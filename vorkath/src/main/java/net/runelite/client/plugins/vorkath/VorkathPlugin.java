package net.runelite.client.plugins.vorkath;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.iutils.*;
import net.runelite.client.plugins.iutils.game.Game;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;

@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
        name = "WaffleVorkath",
        description = "Some QoL to make Vorkath more AFK",
        tags = {"vork", "vorkath"}
)
@Slf4j
public class VorkathPlugin extends Plugin {
    private static final int VORKATH_REGION = 9023;
    private static final Set<Integer> ANTI_VENOM = Set.of(ItemID.ANTIVENOM1_12919, ItemID.ANTIVENOM2_12917, ItemID.ANTIVENOM3_12915, ItemID.ANTIVENOM4_12913);
    private static final Set<Integer> FOOD = Set.of(ItemID.SHARK, ItemID.COOKED_KARAMBWAN);
    private static final Set<Integer> PRAYER = Set.of(ItemID.PRAYER_POTION1, ItemID.PRAYER_POTION2, ItemID.PRAYER_POTION3, ItemID.PRAYER_POTION4);
    private static final Set<Integer> RANGE_BOOST = Set.of(ItemID.DIVINE_RANGING_POTION1, ItemID.DIVINE_RANGING_POTION2, ItemID.DIVINE_RANGING_POTION3, ItemID.DIVINE_RANGING_POTION4);
    private static final Set<Integer> ANTI_FIRE_SET = Set.of(ItemID.ANTIFIRE_POTION1, ItemID.ANTIFIRE_POTION2, ItemID.ANTIFIRE_POTION3, ItemID.ANTIFIRE_POTION4, ItemID.SUPER_ANTIFIRE_POTION1, ItemID.SUPER_ANTIFIRE_POTION2, ItemID.SUPER_ANTIFIRE_POTION3, ItemID.SUPER_ANTIFIRE_POTION4,
            ItemID.EXTENDED_ANTIFIRE1, ItemID.EXTENDED_ANTIFIRE2, ItemID.EXTENDED_ANTIFIRE3, ItemID.EXTENDED_ANTIFIRE4, ItemID.EXTENDED_SUPER_ANTIFIRE1, ItemID.EXTENDED_SUPER_ANTIFIRE2, ItemID.EXTENDED_SUPER_ANTIFIRE3, ItemID.EXTENDED_SUPER_ANTIFIRE4);
    private static final Set<Integer> DIAMOND_SET = Set.of(ItemID.DIAMOND_DRAGON_BOLTS_E, ItemID.DIAMOND_BOLTS_E);
    private static final Set<Integer> RUBY_SET = Set.of(ItemID.RUBY_DRAGON_BOLTS_E, ItemID.RUBY_BOLTS_E);

    @Inject
    private VorkathConfig config;

    @Inject
    private Client client;

    @Inject
    private iUtils utils;

    @Inject
    private WalkUtils walk;

    @Inject
    private InventoryUtils inventory;

    @Inject
    private MenuUtils menu;

    @Inject
    private MouseUtils mouse;

    @Inject
    private PlayerUtils player;

    @Inject
    private ExecutorService executorService;

    @Inject
    protected Game game;

    private int timeout;
    private NPC vorkath;
    private NPC zombifiedSpawn;
    private List<WorldPoint> acidSpots = new ArrayList<>();
    private List<WorldPoint> acidFreePath = new ArrayList<>();
    private boolean freezeAttackSpawned = false;
    private boolean extendedAntifireActive = false;
    private boolean isAcidPhase = false;
    private long ticksSinceEating = 0;
    private boolean isDodgingBomb = false;


    public VorkathPlugin() {
    }

    @Provides
    VorkathConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(VorkathConfig.class);
    }

    @Override
    protected void startUp() {

    }

    @Override
    protected void shutDown() {
        resetVorkath();
    }

    @Subscribe
    private void onGameObjectSpawned(GameObjectSpawned event) {
        final GameObject obj = event.getGameObject();

        if (obj.getId() == ObjectID.ACID_POOL || obj.getId() == ObjectID.ACID_POOL_32000) {
            isAcidPhase = true;
        }
    }

    @Subscribe
    private void onGameObjectDespawned(GameObjectDespawned event) {
        final GameObject obj = event.getGameObject();

        if (obj.getId() == ObjectID.ACID_POOL || obj.getId() == ObjectID.ACID_POOL_32000) {
            isAcidPhase = false;
        }
    }

    @Subscribe
    private void onNpcSpawned(NpcSpawned event) {
        final NPC npc = event.getNpc();

        if (npc.getName() == null) {
            return;
        }

        if (npc.getName().equals("Vorkath")) {
            vorkath = npc;
        } else if (npc.getName().equals("Zombified Spawn")) {
            zombifiedSpawn = npc;

            if (config.killSpawn()) {
                MenuEntry entry = new MenuEntry("Cast", "", npc.getIndex(), MenuAction.SPELL_CAST_ON_NPC.getId(), 0, 0, false);
                utils.oneClickCastSpell(WidgetInfo.SPELL_CRUMBLE_UNDEAD, entry, npc.getConvexHull().getBounds(), 100);
            }
        }
    }

    @Subscribe
    private void onNpcDespawned(NpcDespawned event) {
        final NPC npc = event.getNpc();

        if (npc.getName() == null) {
            return;
        }

        if (npc.getName().equals("Vorkath")) {
            resetVorkath();
        } else if (npc.getName().equals("Zombified Spawn")) {
            zombifiedSpawn = null;
            freezeAttackSpawned = false;
        }
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile.getId() == 395) {
            freezeAttackSpawned = true;
        }
    }

    @Subscribe
    private void onProjectileSpawned(ProjectileSpawned event) {
        final Projectile projectile = event.getProjectile();
        final WorldPoint loc = client.getLocalPlayer().getWorldLocation();
        final LocalPoint localLoc = LocalPoint.fromWorld(client, loc);

        if (projectile.getId() == ProjectileID.VORKATH_BOMB_AOE && config.dodgeBomb()) {
            isDodgingBomb = true;
            LocalPoint dodgeRight = new LocalPoint(localLoc.getX() + 256, localLoc.getY());
            LocalPoint dodgeLeft = new LocalPoint(localLoc.getX() - 256, localLoc.getY());
            if (localLoc.getX() < 6208) {
                walk.sceneWalk(dodgeRight, 0, 0);
            } else {
                walk.sceneWalk(dodgeLeft, 0, 0);
            }
            timeout = 5;
        } else if (projectile.getId() == ProjectileID.VORKATH_ICE) {
            walk.sceneWalk(localLoc, 0, 100);
        }
    }

    @Subscribe
    private void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        if (!isAtVorkath()) {
            resetVorkath();
            return;
        }

        if (client.getVarps()[173] == 1) {
            System.out.println("Toggling run off");
            Widget widget = client.getWidget(WidgetInfo.MINIMAP_TOGGLE_RUN_ORB);
            MenuEntry entry = new MenuEntry("Toggle Run", "", 1, MenuAction.CC_OP.getId(), -1, widget.getId(), false);
            utils.doActionClientTick(entry, widget.getBounds(), 2);
            return;
        }

        if (vorkath == null) {
            return;
        }

        if (timeout > 0) {
            timeout--;
        }

        if (timeout == 1) {
            isDodgingBomb = false;

            if (config.fastRetaliate()) {
                utils.doNpcActionMsTime(vorkath, MenuAction.NPC_SECOND_OPTION.getId(), 0);
                return;
            }

        }

        boolean isVorkathDead = vorkath.isDead() || vorkath.getCombatLevel() == 0;
        boolean isSpawnAlive = zombifiedSpawn == null ? freezeAttackSpawned : !zombifiedSpawn.isDead();
        boolean isSpawnDead = zombifiedSpawn != null && zombifiedSpawn.isDead();
        boolean enableQuickPrayer = !isAcidPhase && !isVorkathDead && !isSpawnAlive;
        boolean canEat = !isVorkathDead;
        boolean canEatBetweenPhase = canEat && (/*isSpawnDead ||*/ isAcidPhase);
        boolean canAttackVorkath = !isAcidPhase && !isVorkathDead && !isSpawnAlive && !isDodgingBomb /*&& isSpawnDead*/;

        if (config.enablePrayer() && client.getBoostedSkillLevel(Skill.PRAYER) > 0) {
            toggleQuickPrayer(enableQuickPrayer);
        }

        WidgetItem itemToEat = null;
        if (canEat) {
            if (itemToEat == null && client.getBoostedSkillLevel(Skill.HITPOINTS) <= 30) {
                itemToEat = inventory.getWidgetItem(FOOD);
                System.out.println("Eating emergency food");
            }

            if (itemToEat == null && client.getBoostedSkillLevel(Skill.PRAYER) <= 5) {
                itemToEat = inventory.getWidgetItem(PRAYER);
                System.out.println("Eating emergency prayer pot");
            }

            if (itemToEat == null && client.getVar(VarPlayer.POISON) >= 1000000) {
                itemToEat = inventory.getWidgetItem(ANTI_VENOM);
                System.out.println("Running out of anti-venom+ ");
            }
        }

        if (canEatBetweenPhase) {
            if (itemToEat == null && (client.getRealSkillLevel(Skill.HITPOINTS) - client.getBoostedSkillLevel(Skill.HITPOINTS)) > 22) {
                itemToEat = inventory.getWidgetItem(FOOD);
                System.out.println("Health is getting low, looking for food to eat");
            }

            if (itemToEat == null && client.getBoostedSkillLevel(Skill.PRAYER) <= 30) {
                itemToEat = inventory.getWidgetItem(PRAYER);
                System.out.println("Prayer is below, looking for prayer pot");
            }

            if (itemToEat == null && client.getBoostedSkillLevel(Skill.RANGED) <= client.getRealSkillLevel(Skill.RANGED)) {
                itemToEat = inventory.getWidgetItem(RANGE_BOOST);
                System.out.println("Activating ranging boost");
            }

            if (itemToEat == null && !extendedAntifireActive) {
                itemToEat = inventory.getWidgetItem(ANTI_FIRE_SET);
                System.out.println("Activating ranging boost");
            }
        }

        if (itemToEat != null && ticksSinceEating > 2) {
            System.out.println("Using item with id: " + itemToEat.getId());
            useItem(itemToEat);
            ticksSinceEating = 0;

            if (canAttackVorkath) {
                System.out.println("Attacking vorkath: " + !isAcidPhase + " " + !isVorkathDead + " " + !isSpawnAlive + " " + isSpawnDead);
                utils.doNpcActionMsTime(vorkath, MenuAction.NPC_SECOND_OPTION.getId(), 600);
            }
            return;
        }

        ticksSinceEating++;

        int health = calculateHealth(vorkath, 750);
        if ((health > 0 || isVorkathDead) && config.switchBolts()) {
            Set<Integer> boltsToEquip = (health < 300 && !isVorkathDead) ? DIAMOND_SET : RUBY_SET;
            if (!player.isItemEquipped(boltsToEquip) && inventory.containsItem(boltsToEquip)) {
                System.out.println("Vorkath health is " + health + ", death status: " + isVorkathDead + ", switching to " + boltsToEquip);
                WidgetItem bolts = inventory.getWidgetItem(boltsToEquip);
                if (bolts != null) {
                    utils.doItemActionMsTime(bolts, MenuAction.ITEM_SECOND_OPTION.getId(), WidgetInfo.INVENTORY.getId(), 100);

                    if (canAttackVorkath) {
                        utils.doNpcActionMsTime(vorkath, MenuAction.NPC_SECOND_OPTION.getId(), 600);
                    }
                }
            }
        }

    }

    @Subscribe
    private void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.SPAM && event.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }

        //  System.out.println(event.getMessage() + " | " + event.getType());
        if (event.getMessage().contains("You drink some of your extended super antifire potion.")) {
            extendedAntifireActive = true;
        } else if (event.getMessage().contains("antifire potion has expired")) {
            extendedAntifireActive = false;
        } else if ((event.getMessage().equals("The spawn violently explodes, unfreezing you as it does so.") || (event.getMessage().equals("You become unfrozen as you kill the spawn.")))) {
            if (config.fastRetaliate()) {
                utils.doNpcActionMsTime(vorkath, MenuAction.NPC_SECOND_OPTION.getId(), 100);
            }
        }
    }


    private boolean isAtVorkath() {
        return client.isInInstancedRegion() && IntStream.of(client.getMapRegions()).anyMatch(x -> x == VORKATH_REGION);
    }


    private void resetVorkath() {
        isAcidPhase = false;
        vorkath = null;
        freezeAttackSpawned = false;
        zombifiedSpawn = null;
        isDodgingBomb = false;
    }

    private void toggleQuickPrayer(boolean enabled) {
        if (client.getVar(Varbits.QUICK_PRAYER) != (enabled ? 1 : 0)) {
            Widget widget = client.getWidget(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB);
            MenuEntry entry = new MenuEntry("Activate", "Quick-prayers", 1, MenuAction.CC_OP.getId(), -1, widget.getId(), false);
            executorService.submit(() -> {
                menu.setEntry(entry);
                mouse.click(widget.getBounds());
            });
        }
    }

    private void useItem(WidgetItem item) {
        if (item != null) {
            MenuEntry targetMenu = new MenuEntry("", "", item.getId(), MenuAction.ITEM_FIRST_OPTION.getId(), item.getIndex(),
                    WidgetInfo.INVENTORY.getId(), false);
            menu.setEntry(targetMenu);
            mouse.handleMouseClick(item.getCanvasBounds());
        }
    }


    private int calculateHealth(NPC target, int maxHealth) {
        // Based on OpponentInfoOverlay HP calculation & taken from the default slayer plugin
        if (target == null || target.getName() == null) {
            return -1;
        }

        final int healthScale = target.getHealthScale();
        final int healthRatio = target.getHealthRatio();

        if (healthRatio < 0 || healthScale <= 0) {
            return -1;
        }

        return (int) ((maxHealth * healthRatio / healthScale) + 0.5f);
    }
}
