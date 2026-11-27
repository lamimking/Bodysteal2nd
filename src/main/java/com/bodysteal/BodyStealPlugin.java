package com.bodysteal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class BodyStealPlugin extends JavaPlugin implements Listener {

    /** Parts are lost in this order: death 1..4. Death 5 = cage. */
    enum Part {
        LEFT_ARM("Left Arm"), RIGHT_ARM("Right Arm"), LEFT_LEG("Left Leg"), RIGHT_LEG("Right Leg");
        final String label;
        Part(String label) { this.label = label; }
    }

    /** A part that originally belongs to someone else, attached to a slot of the holder. */
    record Held(UUID owner, Part part) {
        String enc() { return owner + ":" + part.name(); }
        static Held dec(String s) {
            String[] a = s.split(":");
            return new Held(UUID.fromString(a[0]), Part.valueOf(a[1]));
        }
    }

    static class PD {
        EnumSet<Part> lost = EnumSet.noneOf(Part.class);          // empty slots
        EnumMap<Part, Held> held = new EnumMap<>(Part.class);     // slots filled with others' parts
        boolean caged;
        boolean release;     // revived while offline
        long nextNausea;     // runtime only
    }

    static class Menu implements InventoryHolder {            // revive menu
        @Override public Inventory getInventory() { return null; }
    }

    static class RecipeMenu implements InventoryHolder {      // recipe viewer
        @Override public Inventory getInventory() { return null; }
    }

    private final Map<UUID, PD> store = new HashMap<>();
    private final Map<String, Recipe> recipes = new LinkedHashMap<>();
    private final Map<String, ItemStack> display = new LinkedHashMap<>();
    private final Map<String, String> titles = new LinkedHashMap<>();
    private File dataFile;
    private NamespacedKey PART_KEY, OWNER_KEY, REVIVE_KEY, SAW_KEY, SAW_USES_KEY, BROKEN_KEY, TARGET_KEY, RECIPE_KEY, graftKey;

    @Override
    public void onEnable() {
        PART_KEY = new NamespacedKey(this, "body_part");
        OWNER_KEY = new NamespacedKey(this, "part_owner");
        REVIVE_KEY = new NamespacedKey(this, "revive_item");
        SAW_KEY = new NamespacedKey(this, "bone_saw");
        SAW_USES_KEY = new NamespacedKey(this, "saw_uses");
        BROKEN_KEY = new NamespacedKey(this, "broken_saw");
        graftKey = new NamespacedKey(this, "own_part");
        TARGET_KEY = new NamespacedKey(this, "revive_target");
        RECIPE_KEY = new NamespacedKey(this, "recipe_id");
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        registerRecipes();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() { save(); }

    // ---------------------------------------------------------------- data

    PD pd(UUID id) { return store.computeIfAbsent(id, k -> new PD()); }

    String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "Unknown" : n;
    }

    void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            PD d = new PD();
            for (String n : s.getStringList(key + ".lost")) {
                try { d.lost.add(Part.valueOf(n)); } catch (IllegalArgumentException ignored) { }
            }
            ConfigurationSection hs = s.getConfigurationSection(key + ".held");
            if (hs != null) {
                for (String slot : hs.getKeys(false)) {
                    try { d.held.put(Part.valueOf(slot), Held.dec(hs.getString(slot))); }
                    catch (RuntimeException ignored) { }
                }
            }
            d.caged = s.getBoolean(key + ".caged");
            d.release = s.getBoolean(key + ".release");
            store.put(UUID.fromString(key), d);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        store.forEach((id, d) -> {
            String b = "players." + id;
            y.set(b + ".lost", d.lost.stream().map(Enum::name).toList());
            d.held.forEach((slot, h) -> y.set(b + ".held." + slot.name(), h.enc()));
            y.set(b + ".caged", d.caged);
            y.set(b + ".release", d.release);
        });
        try { y.save(dataFile); } catch (IOException ex) { getLogger().warning(ex.getMessage()); }
    }

    // ---------------------------------------------------------------- items

    /** Points the item at a custom model in the resource pack: bodysteal:<id> (assets/bodysteal/items/<id>.json). */
    void model(ItemMeta m, String id) {
        m.setItemModel(new NamespacedKey(this, id));
    }

    ItemStack reviveItem() {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Revive Heart", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Right-click to free a caged player", NamedTextColor.GRAY)));
        model(m, "revive_heart");
        m.getPersistentDataContainer().set(REVIVE_KEY, PersistentDataType.STRING, "1");
        it.setItemMeta(m);
        return it;
    }

    int sawUses() { return Math.max(1, getConfig().getInt("saw-uses", 3)); }

    ItemStack sawItem(int uses) {
        ItemStack it = new ItemStack(Material.SHEARS);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Bone Saw", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Right-click a player to saw off a part", NamedTextColor.GRAY),
                Component.text("Shift + right-click to cut your own part", NamedTextColor.GRAY),
                Component.text("Uses left: " + uses, NamedTextColor.DARK_GRAY)));
        m.getPersistentDataContainer().set(SAW_KEY, PersistentDataType.STRING, "1");
        m.getPersistentDataContainer().set(SAW_USES_KEY, PersistentDataType.INTEGER, uses);
        model(m, "bone_saw");
        it.setItemMeta(m);
        return it;
    }

    ItemStack brokenSawItem() {
        ItemStack it = new ItemStack(Material.FLINT);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Broken Bone Saw", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Craft with 2 diamonds to repair", NamedTextColor.DARK_GRAY)));
        model(m, "broken_saw");
        m.getPersistentDataContainer().set(BROKEN_KEY, PersistentDataType.STRING, "1");
        it.setItemMeta(m);
        return it;
    }

    ItemStack graftItem() {
        ItemStack it = new ItemStack(Material.BONE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Your Own Body Part", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Put ANOTHER player's part in the middle", NamedTextColor.GRAY),
                Component.text("You get your own part of that type", NamedTextColor.GRAY)));
        model(m, "own_part");
        it.setItemMeta(m);
        return it;
    }

    ItemStack partItem(String ownerName, Part p, UUID owner) {
        ItemStack it = new ItemStack(Material.BONE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(ownerName + "'s " + p.label, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Right-click while missing a part to attach it", NamedTextColor.GRAY),
                Component.text("The owner can use it to take it back", NamedTextColor.DARK_GRAY)));
        m.getPersistentDataContainer().set(PART_KEY, PersistentDataType.STRING, p.name());
        m.getPersistentDataContainer().set(OWNER_KEY, PersistentDataType.STRING, owner.toString());
        model(m, "part_" + p.name().toLowerCase());
        it.setItemMeta(m);
        return it;
    }

    String tag(ItemStack i, NamespacedKey k) {
        if (i == null || !i.hasItemMeta()) return null;
        return i.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------- recipes

    void reg(String id, String title, NamespacedKey k, Recipe r, ItemStack shown) {
        Bukkit.removeRecipe(k);
        Bukkit.addRecipe(r);
        recipes.put(id, r);
        display.put(id, shown);
        titles.put(id, title);
    }

    void addShaped(String id, String title, ItemStack result, Map<Character, Material> ing, String... shape) {
        NamespacedKey k = new NamespacedKey(this, id);
        ShapedRecipe r = new ShapedRecipe(k, result);
        r.shape(shape);
        for (Map.Entry<Character, Material> en : ing.entrySet()) r.setIngredient(en.getKey(), en.getValue());
        reg(id, title, k, r, result);
    }

    void registerRecipes() {
        addShaped("revive_heart", "Revive Heart", reviveItem(),
                Map.of('D', Material.DIAMOND_BLOCK, 'T', Material.TOTEM_OF_UNDYING, 'N', Material.NETHER_STAR),
                "DTD", "TNT", "DTD");
        addShaped("bone_saw", "Bone Saw", sawItem(sawUses()),
                Map.of('I', Material.IRON_INGOT, 'B', Material.BONE, 'D', Material.DIAMOND, 'S', Material.STICK),
                "IBI", "BDB", " S ");

        // repair: broken saw + 2 diamonds
        NamespacedKey fk = new NamespacedKey(this, "fix_saw");
        ShapelessRecipe fix = new ShapelessRecipe(fk, sawItem(sawUses()));
        fix.addIngredient(new RecipeChoice.ExactChoice(brokenSawItem()));
        fix.addIngredient(2, Material.DIAMOND);
        reg("fix_saw", "Repair Saw", fk, fix, sawItem(sawUses()));

        // own part: the middle bone must be ANOTHER player's body part (checked in onCraft)
        ShapedRecipe graft = new ShapedRecipe(graftKey, graftItem());
        graft.shape("BDB", "DCD", "BDB");
        graft.setIngredient('B', Material.BONE_BLOCK);
        graft.setIngredient('D', Material.DIAMOND);
        graft.setIngredient('C', Material.BONE);
        reg("own_part", "Own Body Part", graftKey, graft, graftItem());
    }

    void openRecipes(Player p) {
        Inventory inv = Bukkit.createInventory(new RecipeMenu(), 27, Component.text("BodySteal recipes"));
        int slot = 10;
        for (Map.Entry<String, ItemStack> en : display.entrySet()) {
            ItemStack it = en.getValue().clone();
            ItemMeta m = it.getItemMeta();
            m.lore(List.of(Component.text("Recipe: " + titles.get(en.getKey()), NamedTextColor.GOLD),
                    Component.text("Click to view", NamedTextColor.YELLOW)));
            m.getPersistentDataContainer().set(RECIPE_KEY, PersistentDataType.STRING, en.getKey());
            it.setItemMeta(m);
            inv.setItem(slot++, it);
        }
        p.openInventory(inv);
    }

    void showRecipe(Player p, String id) {
        Recipe r = recipes.get(id);
        Inventory inv = Bukkit.createInventory(new RecipeMenu(), 45,
                Component.text("Recipe: " + titles.get(id)));
        if (r instanceof ShapedRecipe sr) {
            String[] shape = sr.getShape();
            Map<Character, ItemStack> ing = sr.getIngredientMap();
            for (int row = 0; row < shape.length; row++) {
                for (int col = 0; col < shape[row].length(); col++) {
                    ItemStack s = ing.get(shape[row].charAt(col));
                    if (s != null) inv.setItem(11 + row * 9 + col, new ItemStack(s.getType()));
                }
            }
        } else if (r instanceof ShapelessRecipe sl) {
            int i = 0;
            for (ItemStack s : sl.getIngredientList()) {
                ItemStack one = s.clone();
                one.setAmount(1);
                inv.setItem(11 + (i / 3) * 9 + (i % 3), one);
                i++;
            }
        }
        inv.setItem(24, display.get(id).clone());
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta m = back.getItemMeta();
        m.displayName(Component.text("Back", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.getPersistentDataContainer().set(RECIPE_KEY, PersistentDataType.STRING, "back");
        back.setItemMeta(m);
        inv.setItem(40, back);
        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- cage

    Location cage() { return getConfig().getLocation("cage"); }

    void buildCage(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -1; dy <= 3; dy++)
                for (int dz = -2; dz <= 2; dz++) {
                    boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= 0 && dy <= 2;
                    w.getBlockAt(x + dx, y + dy, z + dz).setType(inner ? Material.AIR : Material.BEDROCK);
                }
        getConfig().set("cage", new Location(w, x + 0.5, y, z + 0.5));
        saveConfig();
    }

    boolean inCage(Location l) {
        Location c = cage();
        if (c == null || l.getWorld() != c.getWorld()) return false;
        return Math.abs(l.getX() - c.getX()) <= 1.5 && Math.abs(l.getZ() - c.getZ()) <= 1.5
                && l.getY() >= c.getY() - 0.5 && l.getY() <= c.getY() + 3;
    }

    void revive(UUID id, Location to) {
        PD d = pd(id);
        for (Part p : Part.values()) {
            if (d.lost.contains(p) || d.held.containsKey(p)) reclaim(id, p);
        }
        boolean wasCaged = d.caged;
        d.caged = false;
        d.lost.clear();
        d.held.clear();
        Player p = Bukkit.getPlayer(id);
        if (p != null) setJump(p, false);
        if (wasCaged) {
            if (p != null) p.teleport(to); else d.release = true;
        }
        save();
    }

    // ---------------------------------------------------------------- logic

    Part firstPresent(PD d) {
        for (Part p : Part.values()) if (!d.lost.contains(p)) return p;
        return null;
    }

    boolean missingOwn(PD d, Part p) { return d.lost.contains(p) || d.held.containsKey(p); }

    /** Owner got their part back: whoever was wearing it loses that slot. */
    void reclaim(UUID owner, Part ownerPart) {
        for (Map.Entry<UUID, PD> en : store.entrySet()) {
            PD h = en.getValue();
            Part slot = null;
            for (Map.Entry<Part, Held> he : h.held.entrySet()) {
                if (he.getValue().owner().equals(owner) && he.getValue().part() == ownerPart) { slot = he.getKey(); break; }
            }
            if (slot == null) continue;
            h.held.remove(slot);
            h.lost.add(slot);
            Player hp = Bukkit.getPlayer(en.getKey());
            if (hp != null) hp.sendMessage(Component.text(nameOf(owner) + " took back their "
                    + ownerPart.label + " - you lost your " + slot.label + "!", NamedTextColor.DARK_RED));
        }
    }

    void attach(Player p, PD d, Held h, Part preferSlot) {
        Part slot = (preferSlot != null && d.lost.contains(preferSlot)) ? preferSlot : d.lost.iterator().next();
        d.lost.remove(slot);
        d.held.put(slot, h);
        if (d.nextNausea == 0) d.nextNausea = System.currentTimeMillis() + rnd();
        p.sendMessage(Component.text("You attached " + nameOf(h.owner()) + "'s " + h.part().label
                + ". It is not yours - you will feel sick every few seconds.", NamedTextColor.DARK_RED));
    }

    /** Remove the victim's next part (death or saw) and hand it to the taker, if any. */
    void strip(Player victim, PD d, Player taker) {
        Part slot = firstPresent(d);
        Held origin = d.held.remove(slot);
        UUID ownerId = origin != null ? origin.owner() : victim.getUniqueId();
        Part ownerPart = origin != null ? origin.part() : slot;
        d.lost.add(slot);
        victim.sendMessage(Component.text("You lost your " + slot.label + "! (" + (4 - d.lost.size())
                + " parts left)", NamedTextColor.RED));
        if (taker != null) takePart(taker, ownerId, ownerPart, slot);
    }

    void takePart(Player t, UUID owner, Part ownerPart, Part preferSlot) {
        PD td = pd(t.getUniqueId());
        if (td.caged) return;
        if (owner.equals(t.getUniqueId())) {
            if (td.lost.remove(ownerPart)) t.sendMessage(Component.text("You got your own " + ownerPart.label + " back.", NamedTextColor.GREEN));
            return;
        }
        if (!td.lost.isEmpty()) {
            attach(t, td, new Held(owner, ownerPart), preferSlot);
        } else {
            t.getInventory().addItem(partItem(nameOf(owner), ownerPart, owner)).values()
                    .forEach(i -> t.getWorld().dropItemNaturally(t.getLocation(), i));
            t.sendMessage(Component.text("You took " + nameOf(owner) + "'s " + ownerPart.label + ".", NamedTextColor.GOLD));
        }
    }

    void spendSaw(Player p, ItemStack hand) {
        Integer u = hand.getItemMeta().getPersistentDataContainer().get(SAW_USES_KEY, PersistentDataType.INTEGER);
        int left = (u == null ? sawUses() : u) - 1;
        if (left <= 0) {
            p.getInventory().setItemInMainHand(brokenSawItem());
            p.sendMessage(Component.text("Your Bone Saw broke! Repair it with 2 diamonds (see /recipes).", NamedTextColor.GOLD));
        } else {
            p.getInventory().setItemInMainHand(sawItem(left));
        }
    }

    /** Shift + right-click with the saw: cut your own next part and keep it as an item. */
    void cutSelf(Player p, PD d, ItemStack hand) {
        if (d.caged || noArms(d)) {
            p.sendMessage(Component.text("You can't use the saw without arms.", NamedTextColor.RED));
            return;
        }
        if (d.lost.size() >= 4) {
            p.sendMessage(Component.text("There is nothing left to cut.", NamedTextColor.GRAY));
            return;
        }
        Part slot = firstPresent(d);
        Held origin = d.held.remove(slot);
        UUID ownerId = origin != null ? origin.owner() : p.getUniqueId();
        Part ownerPart = origin != null ? origin.part() : slot;
        d.lost.add(slot);
        p.getInventory().addItem(partItem(nameOf(ownerId), ownerPart, ownerId)).values()
                .forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        p.sendMessage(Component.text("You cut off your " + slot.label + "! (" + (4 - d.lost.size()) + " parts left)", NamedTextColor.RED));
        spendSaw(p, hand);
        save();
    }

    void usePart(Player p, PD d, ItemStack hand, String partName, String ownerStr) {
        Part part = Part.valueOf(partName);
        UUID owner = ownerStr == null ? new UUID(0, 0) : UUID.fromString(ownerStr);

        if (owner.equals(p.getUniqueId())) {                       // my own part
            if (!missingOwn(d, part)) {
                p.sendMessage(Component.text("You still have your " + part.label + ".", NamedTextColor.GRAY));
                return;
            }
            hand.setAmount(hand.getAmount() - 1);
            if (d.held.remove(part) == null) d.lost.remove(part);
            reclaim(owner, part);                                  // whoever wore it loses it
            p.sendMessage(Component.text("Your " + part.label + " grew back!", NamedTextColor.GREEN));
            save();
            return;
        }

        if (d.lost.isEmpty()) {
            p.sendMessage(Component.text("You already have a full body.", NamedTextColor.GRAY));
            return;
        }
        PD od = store.get(owner);
        hand.setAmount(hand.getAmount() - 1);
        if (od != null && !missingOwn(od, part)) {                 // owner already regrew it
            p.sendMessage(Component.text("The part crumbles to dust - its owner already has it back.", NamedTextColor.GRAY));
            return;
        }
        attach(p, d, new Held(owner, part), part);
        save();
    }

    long rnd() {
        int mn = Math.max(1, getConfig().getInt("nausea-min-seconds", 5));
        int mx = Math.max(mn, getConfig().getInt("nausea-max-seconds", 10));
        return (mn + ThreadLocalRandom.current().nextInt(mx - mn + 1)) * 1000L;
    }

    void setJump(Player p, boolean disabled) {
        AttributeInstance j = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (j == null) return;
        double want = disabled ? 0.0 : j.getDefaultValue();
        if (j.getBaseValue() != want) j.setBaseValue(want);
    }

    void eff(Player p, PotionEffectType t, int amp) {
        p.addPotionEffect(new PotionEffect(t, 60, amp, true, false, true));
    }

    void tick() {
        long now = System.currentTimeMillis();
        int nauseaTicks = Math.max(1, getConfig().getInt("nausea-seconds", 4)) * 20;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PD d = pd(p.getUniqueId());
            boolean la = d.lost.contains(Part.LEFT_ARM), ra = d.lost.contains(Part.RIGHT_ARM);
            int legs = (d.lost.contains(Part.LEFT_LEG) ? 1 : 0) + (d.lost.contains(Part.RIGHT_LEG) ? 1 : 0);
            setJump(p, !d.caged && legs == 2);               // no legs = no jumping
            if (d.caged) continue;
            if (la) eff(p, PotionEffectType.WEAKNESS, ra ? 1 : 0);
            if (ra) eff(p, PotionEffectType.MINING_FATIGUE, getConfig().getInt("mining-fatigue-level", 3));
            if (legs >= 1) eff(p, PotionEffectType.SLOWNESS, legs == 2 ? 3 : 1);
            if (!d.held.isEmpty()) {                          // wearing someone else's part
                if (d.nextNausea == 0) d.nextNausea = now + rnd();
                else if (now >= d.nextNausea) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nauseaTicks, 0, true, false, true));
                    d.nextNausea = now + rnd();
                }
            } else d.nextNausea = 0;
        }
    }

    boolean noArms(PD d) { return d.lost.contains(Part.LEFT_ARM) && d.lost.contains(Part.RIGHT_ARM); }

    // ---------------------------------------------------------------- public API (for addons)

    /** Slots this player is missing right now: LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG. */
    public java.util.Set<String> getLostParts(UUID id) {
        java.util.Set<String> r = new java.util.LinkedHashSet<>();
        PD d = store.get(id);
        if (d != null) for (Part p : d.lost) r.add(p.name());
        return r;
    }

    /** Slots filled with someone else's part: slot name -> "ownerUUID:OWNER_PART". */
    public Map<String, String> getHeldParts(UUID id) {
        Map<String, String> r = new LinkedHashMap<>();
        PD d = store.get(id);
        if (d != null) d.held.forEach((slot, h) -> r.put(slot.name(), h.enc()));
        return r;
    }

    public boolean isCaged(UUID id) {
        PD d = store.get(id);
        return d != null && d.caged;
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        PD d = pd(v.getUniqueId());
        if (d.caged) return;

        if (d.lost.size() >= 4) {                       // only torso+head left -> cage
            if (cage() == null) {
                v.sendMessage(Component.text("No cage is set. An admin must run /laps create.", NamedTextColor.RED));
                return;
            }
            d.caged = true;
            save();
            Bukkit.broadcast(Component.text(v.getName() + " lost everything and was locked in the bedrock cage!", NamedTextColor.DARK_RED));
            return;
        }
        Player k = v.getKiller();
        if (k != null && k.equals(v)) k = null;
        strip(v, d, k);
        save();
    }

    @EventHandler
    public void onSaw(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (tag(hand, SAW_KEY) == null) return;
        e.setCancelled(true);
        if (!(e.getRightClicked() instanceof Player t)) return;
        PD pdata = pd(p.getUniqueId()), td = pd(t.getUniqueId());
        if (pdata.caged || noArms(pdata)) {
            p.sendMessage(Component.text("You can't use the saw without arms.", NamedTextColor.RED));
            return;
        }
        if (td.caged || td.lost.size() >= 4) {
            p.sendMessage(Component.text("There is nothing left to saw off.", NamedTextColor.GRAY));
            return;
        }
        strip(t, td, p);
        spendSaw(p, hand);
        save();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PD d = pd(e.getPlayer().getUniqueId());
        if (d.caged && cage() != null) e.setRespawnLocation(cage());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PD d = pd(p.getUniqueId());
        if (d.caged && cage() != null) p.teleport(cage());
        else if (d.release) {
            d.release = false;
            p.teleport(p.getWorld().getSpawnLocation());
            save();
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        PD d = pd(e.getPlayer().getUniqueId());
        Location to = e.getTo();
        if (d.caged) {
            if (cage() != null && !inCage(to)) e.setTo(cage());
            return;
        }
        if (d.lost.size() >= 4) {                       // can't move at all (falling still works)
            Location f = e.getFrom();
            if (to.getX() != f.getX() || to.getZ() != f.getZ()) {
                Location n = to.clone();
                n.setX(f.getX());
                n.setZ(f.getZ());
                e.setTo(n);
            }
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (pd(e.getPlayer().getUniqueId()).caged && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN)
            e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        PD d = pd(e.getPlayer().getUniqueId());
        if (d.caged || noArms(d)) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        PD d = pd(e.getPlayer().getUniqueId());
        if (d.caged || noArms(d)) e.setCancelled(true);
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && noArms(pd(p.getUniqueId()))) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && pd(p.getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (pd(e.getPlayer().getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && pd(p.getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && pd(p.getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        PD d = pd(p.getUniqueId());
        if (d.caged) { e.setCancelled(true); return; }
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (hand == null) return;

        if (tag(hand, SAW_KEY) != null) {
            e.setCancelled(true);
            if (p.isSneaking()) cutSelf(p, d, hand);
            return;
        }
        String part = tag(hand, PART_KEY);
        if (part != null) {
            e.setCancelled(true);
            usePart(p, d, hand, part, tag(hand, OWNER_KEY));
            return;
        }
        if (tag(hand, REVIVE_KEY) != null) {
            e.setCancelled(true);
            openMenu(p);
        }
    }

    void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new Menu(), 54, Component.text("Revive who?"));
        for (Map.Entry<UUID, PD> en : store.entrySet()) {
            if (!en.getValue().caged || inv.firstEmpty() < 0) continue;
            ItemStack s = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) s.getItemMeta();
            OfflinePlayer op = Bukkit.getOfflinePlayer(en.getKey());
            m.setOwningPlayer(op);
            m.displayName(Component.text(String.valueOf(op.getName())));
            m.getPersistentDataContainer().set(TARGET_KEY, PersistentDataType.STRING, en.getKey().toString());
            s.setItemMeta(m);
            inv.addItem(s);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        String t = tag(e.getCurrentItem(), TARGET_KEY);
        if (t == null) return;
        Player p = (Player) e.getWhoClicked();
        ItemStack hand = p.getInventory().getItemInMainHand();
        p.closeInventory();
        if (tag(hand, REVIVE_KEY) == null) return;
        hand.setAmount(hand.getAmount() - 1);
        UUID id = UUID.fromString(t);
        revive(id, p.getLocation());
        Bukkit.broadcast(Component.text(p.getName() + " revived " + nameOf(id) + "!", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler
    public void onRecipeClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RecipeMenu)) return;
        e.setCancelled(true);
        String id = tag(e.getCurrentItem(), RECIPE_KEY);
        if (id == null) return;
        Player p = (Player) e.getWhoClicked();
        if (id.equals("back")) Bukkit.getScheduler().runTask(this, () -> openRecipes(p));
        else if (recipes.containsKey(id)) Bukkit.getScheduler().runTask(this, () -> showRecipe(p, id));
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof RecipeMenu || h instanceof Menu) e.setCancelled(true);
    }

    // body-part items can't be renamed or used as crafting ingredients
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack a = e.getInventory().getFirstItem(), b = e.getInventory().getSecondItem();
        if (tag(a, PART_KEY) != null || tag(b, PART_KEY) != null) e.setResult(null);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        ItemStack[] m = e.getInventory().getMatrix();
        boolean hasPart = false;
        for (ItemStack i : m) if (tag(i, PART_KEY) != null) hasPart = true;

        Recipe rec = e.getRecipe();
        boolean isGraft = rec instanceof ShapedRecipe sr && sr.getKey().equals(graftKey);

        if (isGraft) {
            // own part: the middle item must be ANOTHER player's body part
            if (!(e.getView().getPlayer() instanceof Player p)) return;
            ItemStack center = m.length > 4 ? m[4] : null;
            String ps = tag(center, PART_KEY);
            if (ps == null) { e.getInventory().setResult(null); return; }
            Part part = Part.valueOf(ps);
            String os = tag(center, OWNER_KEY);
            UUID owner = os == null ? new UUID(0, 0) : UUID.fromString(os);
            PD d = pd(p.getUniqueId());
            if (owner.equals(p.getUniqueId()) || !missingOwn(d, part)) { e.getInventory().setResult(null); return; }
            e.getInventory().setResult(partItem(p.getName(), part, p.getUniqueId()));
            return;
        }
        if (hasPart) e.getInventory().setResult(null);     // part items can't go into other recipes
    }

    // ---------------------------------------------------------------- commands

    void openRecipesFor(CommandSender s) {
        if (s instanceof Player p) openRecipes(p); else s.sendMessage("Players only.");
    }

    void help(CommandSender s) {
        s.sendMessage(Component.text("BodySteal commands:", NamedTextColor.GOLD));
        s.sendMessage(Component.text("/recipes  - view all recipes", NamedTextColor.YELLOW));
        if (!s.hasPermission("bodysteal.admin")) return;
        s.sendMessage(Component.text("/laps create  - build the ban cage where you stand", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/laps revive <player>  - free a caged player", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/laps parts <player>  - show a player's parts", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/laps reset <player|all>  - give back all parts", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("/laps giverevive | givesaw | givebrokensaw", NamedTextColor.YELLOW));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("recipes")) { openRecipesFor(s); return true; }
        String sub = a.length == 0 ? "help" : a[0].toLowerCase();
        if (sub.equals("recipes")) { openRecipesFor(s); return true; }
        if (sub.equals("help")) { help(s); return true; }
        if (!s.hasPermission("bodysteal.admin")) {
            s.sendMessage(Component.text("You don't have permission for that.", NamedTextColor.RED));
            help(s);
            return true;
        }
        switch (sub) {
            case "create" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                buildCage(p.getLocation());
                s.sendMessage(Component.text("Bedrock cage created here and set as the ban cage.", NamedTextColor.GREEN));
            }
            case "revive" -> {
                if (a.length < 2) { s.sendMessage("/laps revive <player>"); return true; }
                Location to = s instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                revive(Bukkit.getOfflinePlayer(a[1]).getUniqueId(), to);
                s.sendMessage(Component.text("Revived " + a[1] + ".", NamedTextColor.GREEN));
            }
            case "reset" -> {
                if (a.length < 2) { s.sendMessage("/laps reset <player|all>"); return true; }
                Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
                if (a[1].equalsIgnoreCase("all")) {
                    for (UUID id : new ArrayList<>(store.keySet())) revive(id, spawn);
                    s.sendMessage(Component.text("Everyone has all their parts back.", NamedTextColor.GREEN));
                } else {
                    revive(Bukkit.getOfflinePlayer(a[1]).getUniqueId(), spawn);
                    s.sendMessage(Component.text(a[1] + " has all their parts back.", NamedTextColor.GREEN));
                }
            }
            case "parts" -> {
                if (a.length < 2) { s.sendMessage("/laps parts <player>"); return true; }
                PD d = pd(Bukkit.getOfflinePlayer(a[1]).getUniqueId());
                StringBuilder h = new StringBuilder();
                d.held.forEach((slot, he) -> h.append(slot.label).append("=").append(nameOf(he.owner())).append("'s ").append(he.part().label).append(" "));
                s.sendMessage(Component.text(a[1] + " missing: " + d.lost + " | wearing: " + h + "| caged: " + d.caged));
            }
            case "giverevive" -> { if (s instanceof Player p) p.getInventory().addItem(reviveItem()); }
            case "givesaw" -> { if (s instanceof Player p) p.getInventory().addItem(sawItem(sawUses())); }
            case "givebrokensaw" -> { if (s instanceof Player p) p.getInventory().addItem(brokenSawItem()); }
            default -> help(s);
        }
        return true;
    }

    List<String> playerNames() {
        List<String> r = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) r.add(p.getName());
        for (Map.Entry<UUID, PD> en : store.entrySet()) {
            if (!en.getValue().caged) continue;
            String n = Bukkit.getOfflinePlayer(en.getKey()).getName();
            if (n != null && !r.contains(n)) r.add(n);
        }
        return r;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!c.getName().equalsIgnoreCase("laps")) return List.of();
        boolean admin = s.hasPermission("bodysteal.admin");
        if (a.length == 1) {
            List<String> opts = admin
                    ? List.of("create", "revive", "parts", "reset", "giverevive", "givesaw", "givebrokensaw", "recipes", "help")
                    : List.of("recipes", "help");
            return opts.stream().filter(o -> o.startsWith(a[0].toLowerCase())).toList();
        }
        if (a.length == 2 && admin) {
            String sub = a[0].toLowerCase();
            if (sub.equals("revive") || sub.equals("parts") || sub.equals("reset")) {
                List<String> names = playerNames();
                if (sub.equals("reset")) names.add("all");
                return names.stream().filter(n -> n.toLowerCase().startsWith(a[1].toLowerCase())).toList();
            }
        }
        return List.of();
    }
}
