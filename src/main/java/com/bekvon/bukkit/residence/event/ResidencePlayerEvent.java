/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.bekvon.bukkit.residence.event;

import cn.nukkit.Player;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

/**
 * @author Administrator
 */
public abstract class ResidencePlayerEvent extends ResidenceEvent implements ResidencePlayerEventInterface {

    Player p;

    public ResidencePlayerEvent(String eventName, ClaimedResidence resref, Player player) {
        super(eventName, resref);
        res = resref;
        p = player;
    }

    public boolean isPlayer() {
        return p != null;
    }

    public boolean isAdmin() {
        if (isPlayer()) {
            return Residence.getPermissionManager().isResidenceAdmin(p);
        }
        return true;
    }

    public Player getPlayer() {
        return p;
    }
}
