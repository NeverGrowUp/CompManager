package au.com.addstar.comp.gui.listeners;

import au.com.addstar.comp.CompPlugin;
import au.com.addstar.comp.util.Messages;
import au.com.addstar.comp.util.P2Bridge;
import com.intellectualcrafters.plot.object.Plot;
import com.plotsquared.bukkit.object.BukkitPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;

/**
 * Created for the AddstarMC
 * Created by Narimm on 24/01/2017.
 */
public class PlotMoveClickListener implements ButtonClickListener {

    private final P2Bridge bridge;
    private final Messages messages;
    private boolean prev;

    public PlotMoveClickListener(CompPlugin plugin) {
        this(plugin,false);
    }

    public PlotMoveClickListener(CompPlugin plugin,boolean prev) {
        bridge = plugin.getBridge();
        messages = plugin.messages;
        this.prev = prev;
    }

    @Override
    public void onClick(Player player) {
        Plot plot = bridge.getPlotAt(player.getLocation());
        Plot tpPlot = null;
        if(plot != null) {
            if (prev) {
                tpPlot = getPrevPlot(plot);
            } else {
                tpPlot = getNextPlot(plot);
            }
            if (tpPlot == null) {
                player.sendMessage(messages.get("teleport.no.more.plots"));
                return;
            }
        }else{
            player.sendMessage(messages.get("teleport.noPlot"));
        }
        if (tpPlot != null) {
            tpPlot.teleportPlayer(new BukkitPlayer(player));
            player.sendMessage(messages.get("teleport.next.plot"));
            player.sendMessage("Owned by " + Bukkit.getOfflinePlayer(tpPlot.guessOwner()).getName());
        }
    }

    private Plot getNextPlot(Plot plot) {
        Iterable<Plot> plots = bridge.getOwnedPlots();
        boolean found = false;
        for (Plot newPlot : plots) {
            if (found) {
                return newPlot;
            }
            if(plot == null)  return newPlot;
            if (newPlot.getId() == plot.getId()) found = true;
            }
        return null;
    }
    private Plot getPrevPlot(Plot plot) {
        ArrayList<Plot> plots = bridge.getOwnedPlots();
        boolean found = false;
        int i = 0;
        for (Plot newPlot : plots) {

            if (newPlot.getId() == plot.getId()) found = true;
            if (found) {
                if (i>0)return plots.get(i-1);
            }
            i++;
        }
        return null;
    }
}

