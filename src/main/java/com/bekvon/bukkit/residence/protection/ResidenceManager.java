/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.bekvon.bukkit.residence.protection;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.utils.MainLogger;
import cn.nukkit.utils.TextFormat;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent.DeleteCause;
import com.bekvon.bukkit.residence.event.ResidenceRenameEvent;
import com.bekvon.bukkit.residence.permissions.PermissionGroup;
import com.bekvon.bukkit.residence.text.help.InformationPager;

import java.util.*;
import java.util.Map.Entry;

/**
 * @author Administrator
 */
public class ResidenceManager {

    protected Map<String, ClaimedResidence> residences;
    protected Map<String, Map<ChunkRef, List<String>>> chunkResidences;

    public ResidenceManager() {
        residences = new HashMap<>();
        chunkResidences = new HashMap<>();
    }

    public ClaimedResidence getByLoc(Position loc) {
        if (loc == null) {
            return null;
        }
        ClaimedResidence res = null;
        String world = loc.getLevel().getName();
        ChunkRef chunk = new ChunkRef(loc);
        if (chunkResidences.containsKey(world)) {
            if (chunkResidences.get(world).containsKey(chunk)) {
                for (String key : chunkResidences.get(world).get(chunk)) {
                    ClaimedResidence entry = residences.get(key);
                    if (entry.containsLoc(loc)) {
                        res = entry;
                        break;
                    }
                }
            }
        }
        if (res == null) {
            return null;
        }

        ClaimedResidence subres = res.getSubzoneByLoc(loc);
        if (subres == null) {
            return res;
        }
        return subres;
    }

    public ClaimedResidence getByName(String name) {
        if (name == null) {
            return null;
        }
        String[] split = name.split("\\.");
        if (split.length == 1) {
            return residences.get(name);
        }
        ClaimedResidence res = residences.get(split[0]);
        for (int i = 1; i < split.length; i++) {
            if (res != null) {
                res = res.getSubzone(split[i]);
            } else {
                return null;
            }
        }
        return res;
    }

    public String getNameByLoc(Location loc) {
        ClaimedResidence res = this.getByLoc(loc);
        if (res == null) {
            return null;
        }
        String name = res.getName();
        if (name == null) {
            return null;
        }
        String szname = res.getSubzoneNameByLoc(loc);
        if (szname != null) {
            return name + "." + szname;
        }
        return name;
    }

    public String getNameByRes(ClaimedResidence res) {
        Set<Entry<String, ClaimedResidence>> set = residences.entrySet();
        for (Entry<String, ClaimedResidence> check : set) {
            if (check.getValue() == res) {
                return check.getKey();
            }
            String n = check.getValue().getSubzoneNameByRes(res);
            if (n != null) {
                return check.getKey() + "." + n;
            }
        }
        return null;
    }

    public boolean addResidence(String name, Position loc1, Position loc2) {
        return this.addResidence(name, "Server Land", loc1, loc2);
    }

    public boolean addResidence(String name, String owner, Position loc1, Position loc2) {
        return this.addResidence(null, owner, name, loc1, loc2, true);
    }

    public boolean addResidence(Player player, String name, Position loc1, Position loc2, boolean resadmin) {
        return this.addResidence(player, player.getName(), name, loc1, loc2, resadmin);
    }

    public boolean addResidence(Player player, String owner, String name, Position loc1, Position loc2, boolean resadmin) {
        if (!Residence.validName(name)) {
            if (player != null) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("InvalidNameCharacters"));
            }
            return false;
        }
        if (loc1 == null || loc2 == null || !loc1.getLevel().getName().equals(loc2.getLevel().getName())) {
            if (player != null) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("SelectPoints"));
            }
            return false;
        }
        PermissionGroup group = Residence.getPermissionManager().getGroup(owner, loc1.getLevel().getName());
        boolean createpermission = group.canCreateResidences() || (player == null ? true : player.hasPermission("residence.create"));
        if (!createpermission && !resadmin) {
            if (player != null) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("NoPermission"));
            }
            return false;
        }
        if (player != null) {
            if (getOwnedZoneCount(player.getName()) >= group.getMaxZones() && !resadmin) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("ResidenceTooMany"));
                return false;
            }
        }
        CuboidArea newArea = new CuboidArea(loc1, loc2);
        ClaimedResidence newRes = new ClaimedResidence(owner, loc1.getLevel().getName());
        newRes.getPermissions().applyDefaultFlags();
        newRes.setEnterMessage(group.getDefaultEnterMessage());
        newRes.setLeaveMessage(group.getDefaultLeaveMessage());

        ResidenceCreationEvent resevent = new ResidenceCreationEvent(player, name, newRes, newArea);
        Residence.getServ().getPluginManager().callEvent(resevent);
        if (resevent.isCancelled()) {
            return false;
        }
        newArea = resevent.getPhysicalArea();
        name = resevent.getResidenceName();
        if (residences.containsKey(name)) {
            if (player != null) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("ResidenceAlreadyExists", TextFormat.YELLOW + name + TextFormat.RED));
            }
            return false;
        }
        if (player != null) {
            newRes.addArea(player, newArea, "main", resadmin);
        } else {
            newRes.addArea(newArea, "main");
        }
        if (newRes.getAreaCount() != 0) {
            residences.put(name, newRes);
            calculateChunks(name);
            Residence.getLeaseManager().removeExpireTime(name);
            if (player != null) {
                player.sendMessage(TextFormat.GREEN + Residence.getLanguage().getPhrase("ResidenceCreate", TextFormat.YELLOW + name + TextFormat.GREEN));
            }
            if (Residence.getConfigManager().useLeases()) {
                if (player != null) {
                    Residence.getLeaseManager().setExpireTime(player, name, group.getLeaseGiveTime());
                } else {
                    Residence.getLeaseManager().setExpireTime(name, group.getLeaseGiveTime());
                }
            }
            return true;
        }
        return false;
    }

    public void listResidences(Player player) {
        this.listResidences(player, player.getName(), 1);
    }

    public void listResidences(Player player, int page) {
        this.listResidences(player, player.getName(), page);
    }

    public void listResidences(Player player, String targetplayer) {
        this.listResidences(player, targetplayer, 1);
    }

    public void listResidences(Player player, String targetplayer, int page) {
        this.listResidences(player, targetplayer, page, false);
    }

    public void listResidences(Player player, int page, boolean showhidden) {
        this.listResidences(player, player.getName(), page, showhidden);
    }

    public void listResidences(Player player, String targetplayer, int page, boolean showhidden) {
        this.listResidences(player, targetplayer, page, showhidden, false);
    }

    public void listResidences(Player player, String targetplayer, int page, boolean showhidden, boolean showsubzones) {
        if (showhidden && !Residence.isResAdminOn(player) && !player.getName().equals(targetplayer)) {
            showhidden = false;
        }
        InformationPager.printInfo(player, Residence.getLanguage().getPhrase("Residences") + " - " + targetplayer, this.getResidenceList(targetplayer, showhidden, showsubzones, true), page);
    }

    public void listAllResidences(Player player, int page) {
        this.listAllResidences(player, page, false);
    }

    public void listAllResidences(Player player, int page, boolean showhidden) {
        this.listAllResidences(player, page, showhidden, false);
    }

    public void listAllResidences(Player player, int page, boolean showhidden, boolean showsubzones) {
        if (showhidden && !Residence.isResAdminOn(player)) {
            showhidden = false;
        }
        InformationPager.printInfo(player, Residence.getLanguage().getPhrase("Residences"), this.getResidenceList(null, showhidden, showsubzones, true), page);
    }

    public String[] getResidenceList() {
        return this.getResidenceList(true, true).toArray(new String[0]);
    }

    public ArrayList<String> getResidenceList(boolean showhidden, boolean showsubzones) {
        return this.getResidenceList(null, showhidden, showsubzones, false);
    }

    public ArrayList<String> getResidenceList(String targetplayer, boolean showhidden, boolean showsubzones) {
        return this.getResidenceList(targetplayer, showhidden, showsubzones, false);
    }

    public ArrayList<String> getResidenceList(String targetplayer, boolean showhidden, boolean showsubzones, boolean formattedOutput) {
        ArrayList<String> list = new ArrayList<>();
        for (Entry<String, ClaimedResidence> res : residences.entrySet()) {
            this.getResidenceList(targetplayer, showhidden, showsubzones, "", res.getKey(), res.getValue(), list, formattedOutput);
        }
        return list;
    }

    private void getResidenceList(String targetplayer, boolean showhidden, boolean showsubzones, String parentzone, String resname, ClaimedResidence res, ArrayList<String> list, boolean formattedOutput) {
        boolean hidden = res.getPermissions().has("hidden", false);
        if ((showhidden) || (!showhidden && !hidden)) {
            if (targetplayer == null || res.getPermissions().getOwner().equalsIgnoreCase(targetplayer)) {
                if (formattedOutput) {
                    list.add(TextFormat.GREEN + parentzone + resname + TextFormat.YELLOW + " - " + Residence.getLanguage().getPhrase("World") + ": " + res.getWorld());
                } else {
                    list.add(parentzone + resname);
                }
            }
            if (showsubzones) {
                for (Entry<String, ClaimedResidence> sz : res.subzones.entrySet()) {
                    this.getResidenceList(targetplayer, showhidden, showsubzones, parentzone + resname + ".", sz.getKey(), sz.getValue(), list, formattedOutput);
                }
            }
        }
    }

    public String checkAreaCollision(CuboidArea newarea, ClaimedResidence parentResidence) {
        Set<Entry<String, ClaimedResidence>> set = residences.entrySet();
        for (Entry<String, ClaimedResidence> entry : set) {
            ClaimedResidence check = entry.getValue();
            if (check != parentResidence && check.checkCollision(newarea)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void removeResidence(String name) {
        this.removeResidence(null, name, true);
    }

    public void removeResidence(Player player, String name, boolean resadmin) {
        ClaimedResidence res = this.getByName(name);
        if (res != null) {
            if (player != null && !resadmin) {
                if (!res.getPermissions().hasResidencePermission(player, true) && !resadmin) {
                    player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("NoPermission"));
                    return;
                }
            }
            ResidenceDeleteEvent resevent = new ResidenceDeleteEvent(player, res, player == null ? DeleteCause.OTHER : DeleteCause.PLAYER_DELETE);
            Residence.getServ().getPluginManager().callEvent(resevent);
            if (resevent.isCancelled()) {
                return;
            }
            ClaimedResidence parent = res.getParent();
            if (parent == null) {
                removeChunkList(name);
                residences.remove(name);
                if (player != null) {
                    player.sendMessage(TextFormat.GREEN + Residence.getLanguage().getPhrase("ResidenceRemove", TextFormat.YELLOW + name + TextFormat.GREEN));
                }
            } else {
                String[] split = name.split("\\.");
                if (player != null) {
                    parent.removeSubzone(player, split[split.length - 1], true);
                } else {
                    parent.removeSubzone(split[split.length - 1]);
                }
            }
            // Residence.getLeaseManager().removeExpireTime(name); - causing
            // concurrent modification exception in lease manager... worked
            // around for now
            Residence.getRentManager().removeRentable(name);

        } else {
            if (player != null) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("InvalidResidence"));
            }
        }
    }

    public void removeAllByOwner(String owner) {
        this.removeAllByOwner(null, owner, residences);
    }

    public void removeAllByOwner(Player player, String owner) {
        this.removeAllByOwner(player, owner, residences);
    }

    private void removeAllByOwner(Player player, String owner, Map<String, ClaimedResidence> resholder) {
        Iterator<ClaimedResidence> it = resholder.values().iterator();
        while (it.hasNext()) {
            ClaimedResidence res = it.next();
            if (res.getOwner().equalsIgnoreCase(owner)) {
                ResidenceDeleteEvent resevent = new ResidenceDeleteEvent(player, res, player == null ? DeleteCause.OTHER : DeleteCause.PLAYER_DELETE);
                Residence.getServ().getPluginManager().callEvent(resevent);
                if (resevent.isCancelled()) {
                    return;
                }
                removeChunkList(res.getName());
                it.remove();
            } else {
                this.removeAllByOwner(player, owner, res.subzones);
            }
        }
    }

    public int getOwnedZoneCount(String player) {
        Collection<ClaimedResidence> set = residences.values();
        int count = 0;
        for (ClaimedResidence res : set) {
            if (res.getPermissions().getOwner().equalsIgnoreCase(player)) {
                count++;
            }
        }
        return count;
    }

    public void printAreaInfo(String areaname, Player player) {
        ClaimedResidence res = this.getByName(areaname);
        if (res == null) {
            player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("InvalidResidence"));
            return;
        }
        ResidencePermissions perms = res.getPermissions();
        if (Residence.getConfigManager().enableEconomy()) {
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Residence") + ":" + TextFormat.DARK_GREEN + " " + areaname + " " + TextFormat.YELLOW + "Bank: " + TextFormat.GOLD + res.getBank().getStoredMoney());
        } else {
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Residence") + ":" + TextFormat.DARK_GREEN + " " + areaname);
        }
        if (Residence.getConfigManager().enabledRentSystem() && Residence.getRentManager().isRented(areaname)) {
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Owner") + ":" + TextFormat.RED + " " + perms.getOwner() + TextFormat.YELLOW + " Rented by: " + TextFormat.RED + Residence.getRentManager().getRentingPlayer(areaname));
        } else {
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Owner") + ":" + TextFormat.RED + " " + perms.getOwner() + TextFormat.YELLOW + " - " + Residence.getLanguage().getPhrase("World") + ": " + TextFormat.RED + perms.getLevel());
        }
        player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Flags") + ":" + TextFormat.BLUE + " " + perms.listFlags());
        player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Your.Flags") + ": " + TextFormat.GREEN + perms.listPlayerFlags(player.getName()));
        player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Group.Flags") + ":" + TextFormat.RED + " " + perms.listGroupFlags());
        player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Others.Flags") + ":" + TextFormat.RED + " " + perms.listOtherPlayersFlags(player.getName()));
        String aid = res.getAreaIDbyLoc(player.getLocation());
        if (aid != null) {
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("CurrentArea") + ": " + TextFormat.GOLD + aid);
        }
        player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("Total.Size") + ":" + TextFormat.LIGHT_PURPLE + " " + res.getTotalSize());
        if (aid != null) {
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("CoordsT") + ": " + TextFormat.LIGHT_PURPLE + Residence.getLanguage().getPhrase("CoordsTop", res.getAreaByLoc(player.getLocation()).getHighLoc().getFloorX() + "." + res.getAreaByLoc(player.getLocation()).getHighLoc().getFloorY() + "." + res.getAreaByLoc(player.getLocation()).getHighLoc().getFloorZ()));
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("CoordsB") + ": " + TextFormat.LIGHT_PURPLE + Residence.getLanguage().getPhrase("CoordsBottom", res.getAreaByLoc(player.getLocation()).getLowLoc().getFloorX() + "." + res.getAreaByLoc(player.getLocation()).getLowLoc().getFloorY() + "." + res.getAreaByLoc(player.getLocation()).getLowLoc().getFloorZ()));
        }
        if (Residence.getConfigManager().useLeases() && Residence.getLeaseManager().leaseExpires(areaname)) {
            player.sendMessage(TextFormat.YELLOW + Residence.getLanguage().getPhrase("LeaseExpire") + ":" + TextFormat.GREEN + " " + Residence.getLeaseManager().getExpireTime(areaname));
        }
    }

    public void mirrorPerms(Player reqPlayer, String targetArea, String sourceArea, boolean resadmin) {
        ClaimedResidence reciever = this.getByName(targetArea);
        ClaimedResidence source = this.getByName(sourceArea);
        if (source == null || reciever == null) {
            reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("InvalidResidence"));
            return;
        }
        if (!resadmin) {
            if (!reciever.getPermissions().hasResidencePermission(reqPlayer, true) || !source.getPermissions().hasResidencePermission(reqPlayer, true)) {
                reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("NoPermission"));
                return;
            }
        }
        reciever.getPermissions().applyTemplate(reqPlayer, source.getPermissions(), resadmin);
    }

    public Map<String, Object> save() {
        Map<String, Object> worldmap = new LinkedHashMap<>();
        for (Level level : Residence.getServ().getLevels().values()) {
            Map<String, Object> resmap = new LinkedHashMap<>();
            for (Entry<String, ClaimedResidence> res : residences.entrySet()) {
                if (res.getValue().getWorld().equals(level.getName())) {
                    try {
                        resmap.put(res.getKey(), res.getValue().save());
                    } catch (Exception ex) {
                        MainLogger.getLogger().error("[Residence] Failed to save residence (" + res.getKey() + ")!");
                        MainLogger.getLogger().logException(ex);
                    }
                }
            }
            worldmap.put(level.getName(), resmap);
        }
        return worldmap;
    }

    public static ResidenceManager load(Map<String, Object> root) throws Exception {
        ResidenceManager resm = new ResidenceManager();
        if (root == null) {
            return resm;
        }
        for (Level level : Residence.getServ().getLevels().values()) {
            Map<String, Object> reslist = (Map<String, Object>) root.get(level.getName());
            if (reslist != null) {
                try {
                    resm.chunkResidences.put(level.getName(), loadMap(reslist, resm));
                } catch (Exception ex) {
                    MainLogger.getLogger().error("Error in loading save file for world: " + level.getName());
                    if (Residence.getConfigManager().stopOnSaveError()) {
                        throw (ex);
                    }
                }
            }
        }
        return resm;
    }

    public static Map<ChunkRef, List<String>> loadMap(Map<String, Object> root, ResidenceManager resm) throws Exception {
        Map<ChunkRef, List<String>> retRes = new HashMap<>();
        if (root != null) {
            for (Entry<String, Object> res : root.entrySet()) {
                try {
                    ClaimedResidence residence = ClaimedResidence.load((Map<String, Object>) res.getValue(), null);
                    for (ChunkRef chunk : getChunks(residence)) {
                        List<String> ress = new ArrayList<>();
                        if (retRes.containsKey(chunk)) {
                            ress.addAll(retRes.get(chunk));
                        }
                        ress.add(res.getKey());
                        retRes.put(chunk, ress);
                    }
                    resm.residences.put(res.getKey(), residence);
                } catch (Exception ex) {
                    System.out.print("[Residence] Failed to load residence (" + res.getKey() + ")! Reason:" + ex.getMessage() + " Error Log:");
                    MainLogger.getLogger().logException(ex);
                    if (Residence.getConfigManager().stopOnSaveError()) {
                        throw (ex);
                    }
                }
            }
        }
        return retRes;
    }

    private static List<ChunkRef> getChunks(ClaimedResidence res) {
        List<ChunkRef> chunks = new ArrayList<>();
        for (CuboidArea area : res.getAreaArray()) {
            chunks.addAll(area.getChunks());
        }
        return chunks;
    }

    public boolean renameResidence(String oldName, String newName) {
        return this.renameResidence(null, oldName, newName, true);
    }

    public boolean renameResidence(Player player, String oldName, String newName, boolean resadmin) {
        if (!Residence.validName(newName)) {
            player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("InvalidNameCharacters"));
            return false;
        }
        ClaimedResidence res = this.getByName(oldName);
        if (res == null) {
            if (player != null) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("InvalidResidence"));
            }
            return false;
        }
        if (res.getPermissions().hasResidencePermission(player, true) || resadmin) {
            if (res.getParent() == null) {
                if (residences.containsKey(newName)) {
                    if (player != null) {
                        player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("ResidenceAlreadyExists", TextFormat.YELLOW + newName + TextFormat.RED));
                    }
                    return false;
                }
                ResidenceRenameEvent resevent = new ResidenceRenameEvent(res, newName, oldName);
                Residence.getServ().getPluginManager().callEvent(resevent);
                removeChunkList(oldName);
                residences.put(newName, res);
                residences.remove(oldName);
                calculateChunks(newName);
                if (Residence.getConfigManager().useLeases()) {
                    Residence.getLeaseManager().updateLeaseName(oldName, newName);
                }
                if (Residence.getConfigManager().enabledRentSystem()) {
                    Residence.getRentManager().updateRentableName(oldName, newName);
                }
                if (player != null) {
                    player.sendMessage(TextFormat.GREEN + Residence.getLanguage().getPhrase("ResidenceRename", TextFormat.YELLOW + oldName + TextFormat.GREEN + "." + TextFormat.YELLOW + newName + TextFormat.GREEN));
                }
                return true;
            } else {
                String[] oldname = oldName.split("\\.");
                ClaimedResidence parent = res.getParent();
                return parent.renameSubzone(player, oldname[oldname.length - 1], newName, resadmin);
            }
        } else {
            if (player != null) {
                player.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("NoPermission"));
            }
            return false;
        }
    }

    public void giveResidence(Player reqPlayer, String targPlayer, String residence, boolean resadmin) {
        ClaimedResidence res = getByName(residence);
        if (res == null) {
            reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("InvalidResidence"));
            return;
        }
        if (!res.getPermissions().hasResidencePermission(reqPlayer, true) && !resadmin) {
            reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("NoPermission"));
            return;
        }
        Player giveplayer = Residence.getServ().getPlayer(targPlayer);
        if (giveplayer == null || !giveplayer.isOnline()) {
            reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("NotOnline"));
            return;
        }
        CuboidArea[] areas = res.getAreaArray();
        PermissionGroup g = Residence.getPermissionManager().getGroup(giveplayer);
        if (areas.length > g.getMaxPhysicalPerResidence() && !resadmin) {
            reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("ResidenceGiveLimits"));
            return;
        }
        if (getOwnedZoneCount(giveplayer.getName()) >= g.getMaxZones() && !resadmin) {
            reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("ResidenceGiveLimits"));
            return;
        }
        if (!resadmin) {
            for (CuboidArea area : areas) {
                if (!g.inLimits(area)) {
                    reqPlayer.sendMessage(TextFormat.RED + Residence.getLanguage().getPhrase("ResidenceGiveLimits"));
                    return;
                }
            }
        }
        res.getPermissions().setOwner(giveplayer.getName(), true);
        // Fix phrases here
        reqPlayer.sendMessage(TextFormat.GREEN + Residence.getLanguage().getPhrase("ResidenceGive", TextFormat.YELLOW + residence + TextFormat.GREEN + "." + TextFormat.YELLOW + giveplayer.getName() + TextFormat.GREEN));
        giveplayer.sendMessage(Residence.getLanguage().getPhrase("ResidenceRecieve", TextFormat.GREEN + residence + TextFormat.YELLOW + "." + TextFormat.GREEN + reqPlayer.getName() + TextFormat.YELLOW));
    }

    public void removeAllFromWorld(CommandSender sender, String world) {
        int count = 0;
        Iterator<ClaimedResidence> it = residences.values().iterator();
        while (it.hasNext()) {
            ClaimedResidence next = it.next();
            if (next.getWorld().equals(world)) {
                it.remove();
                count++;
            }
        }
        chunkResidences.remove(world);
        chunkResidences.put(world, new HashMap<ChunkRef, List<String>>());
        if (count == 0) {
            sender.sendMessage(TextFormat.RED + "No residences found in world: " + TextFormat.YELLOW + world);
        } else {
            sender.sendMessage(TextFormat.RED + "Removed " + TextFormat.YELLOW + count + TextFormat.RED + " residences in world: " + TextFormat.YELLOW + world);
        }
    }

    public int getResidenceCount() {
        return residences.size();
    }

    public void removeChunkList(String name) {
        ClaimedResidence res = residences.get(name);
        if (res != null) {
            String world = res.getWorld();
            if (chunkResidences.get(world) != null) {
                for (ChunkRef chunk : getChunks(res)) {
                    List<String> ress = new ArrayList<>();
                    if (chunkResidences.get(world).containsKey(chunk)) {
                        ress.addAll(chunkResidences.get(world).get(chunk));
                    }
                    ress.remove(name);
                    chunkResidences.get(world).put(chunk, ress);
                }
            }
        }
    }

    public void calculateChunks(String name) {
        ClaimedResidence res = residences.get(name);
        if (res != null) {
            String world = res.getWorld();
            if (chunkResidences.get(world) == null) {
                chunkResidences.put(world, new HashMap<ChunkRef, List<String>>());
            }
            for (ChunkRef chunk : getChunks(res)) {
                List<String> ress = new ArrayList<>();
                if (chunkResidences.get(world).containsKey(chunk)) {
                    ress.addAll(chunkResidences.get(world).get(chunk));
                }
                ress.add(name);
                chunkResidences.get(world).put(chunk, ress);
            }
        }
    }

    public static final class ChunkRef {

        public static int getChunkCoord(final int val) {
            // For more info, see CraftBukkit.CraftWorld.getChunkAt( Location )
            return val >> 4;
        }

        private final int z;
        private final int x;

        public ChunkRef(Vector3 loc) {
            this.x = getChunkCoord(loc.getFloorX());
            this.z = getChunkCoord(loc.getFloorZ());
        }

        public ChunkRef(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ChunkRef other = (ChunkRef) obj;
            return this.x == other.x && this.z == other.z;
        }

        @Override
        public int hashCode() {
            return x ^ z;
        }

        /**
         * Useful for debug
         *
         * @return
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{ x: ").append(x).append(", z: ").append(z).append(" }");
            return sb.toString();
        }
    }
}
