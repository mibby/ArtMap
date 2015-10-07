package me.Fupery.ArtMap;

import me.Fupery.ArtMap.Command.CommandListener;
import me.Fupery.ArtMap.Easel.Easel;
import me.Fupery.ArtMap.Easel.Recipe;
import me.Fupery.ArtMap.IO.MapArt;
import me.Fupery.ArtMap.IO.WorldMap;
import me.Fupery.ArtMap.Listeners.*;
import me.Fupery.ArtMap.Protocol.ArtistHandler;
import me.Fupery.ArtMap.Utils.PixelTable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ArtMap extends JavaPlugin {

    public static String entityTag = "Easel";
    private File data;
    private File mapList;
    private FileConfiguration maps;
    private List<String> titleFilter;
    private int backgroundID;
    private ConcurrentHashMap<Player, String> nameQueue;
    private ConcurrentHashMap<Location, Easel> easels;
    private ConcurrentHashMap<Player, MapPreview> previewing;
    private ArtistHandler artistHandler;
    private int mapResolutionFactor;
    private PixelTable pixelTable;

    @Override
    public void onEnable() {

        Recipe.setupRecipes(this);

        PluginManager manager = getServer().getPluginManager();
        manager.registerEvents(new PlayerInteractListener(this), this);
        manager.registerEvents(new PlayerInteractEaselListener(this), this);
        manager.registerEvents(new CanvasListener(this), this);
        manager.registerEvents(new PlayerQuitListener(this), this);
        manager.registerEvents(new ChunkUnloadListener(this), this);
        manager.registerEvents(new PlayerCraftListener(this), this);
        manager.registerEvents(new InventoryInteractListener(this), this);

        this.getCommand("artmap").setExecutor(new CommandListener(this));

        easels = new ConcurrentHashMap<>();
        previewing = new ConcurrentHashMap<>();

        if (setupRegistry()) {
            maps = YamlConfiguration.loadConfiguration(mapList);

            if (maps.getConfigurationSection("artworks") == null) {
                maps.createSection("artworks");
                updateMaps();
            }
        }
        backgroundID = getConfig().getInt("backgroundID");
        int factor = getConfig().getInt("mapResolutionFactor");

        if (factor % 16 == 0 && factor <= 128) {
            mapResolutionFactor = 128 / factor;

        } else {
            mapResolutionFactor = 4;
            getLogger().warning("Invalid mapResolutionFactor in config, default will be used");
        }
        loadTables();

        nameQueue = new ConcurrentHashMap<>();
    }

    @Override
    public void onDisable() {

        if (artistHandler != null) {
            artistHandler.getProtocol().close();
        }

        if (previewing != null && previewing.size() > 0) {

            for (Player player : previewing.keySet()) {
                stopPreviewing(player);
            }
        }
        FileConfiguration config = getConfig();

        if (backgroundID != config.getInt("backgroundID")) {
            config.set("backgroundID", backgroundID);
            saveDefaultConfig();
        }
    }

    private boolean setupRegistry() {

        saveDefaultConfig();

        mapResolutionFactor = getConfig().getInt("mapResolutionFactor");

        mapList = new File(getDataFolder(), "mapList.yml");

        data = new File(getDataFolder(), "data");

        FileConfiguration filter =
                YamlConfiguration.loadConfiguration(getTextResource("titleFilter.yml"));

        titleFilter = filter.getStringList("blacklisted");

        try {

            if (!mapList.exists()) {

                if (!mapList.createNewFile()) {
                    return false;
                }
            }

        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public FileConfiguration getMaps() {
        return maps;
    }

    private boolean loadTables() {

        getLogger().info("Loading pixel tables ...");

        final File pixelTables = new File(data, mapResolutionFactor + "_tables.dat");

        if (data.exists() && pixelTables.exists()) {

            try {
                ObjectInputStream in = new ObjectInputStream(
                        new GZIPInputStream(new FileInputStream(pixelTables)));

                pixelTable = (PixelTable) in.readObject();

                in.close();
                return true;

            } catch (ClassNotFoundException | IOException e) {
                getLogger().warning("Pixel data files corrupted");
            }
        }
        getLogger().warning("No pixel tables found, generating tables ...");
        getLogger().warning("(This will only need be done once)");

        pixelTable = new PixelTable(mapResolutionFactor);
        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                pixelTable.generate();
                saveTables(pixelTables);
                getLogger().warning("Table generation successful!");
            }
        });
        return true;
    }

    private void saveTables(File datafile) {

        if (!data.exists()) {
            data.mkdir();
        }

        try {

            if (datafile.exists()) {
                datafile.delete();
            }
            datafile.createNewFile();

            ObjectOutputStream out = new ObjectOutputStream(
                    new GZIPOutputStream(new FileOutputStream(datafile)));
            out.writeObject(pixelTable);
            out.flush();
            out.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void updateMaps() {
        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {

            @Override
            public void run() {

                try {
                    maps.save(mapList);
                    maps = YamlConfiguration.loadConfiguration(mapList);

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void setBackgroundID(int id) {
        backgroundID = id;
        getConfig().set("backgroundID", id);
        saveDefaultConfig();
    }

    public int getMapResolutionFactor() {
        return mapResolutionFactor;
    }

    public void setupBackgroundID(World world) {
        MapView mapView = Bukkit.createMap(world);
        WorldMap map = new WorldMap(mapView);
        map.setBlankMap();
        setBackgroundID(mapView.getId());
    }

    public void startPreviewing(Player player, MapArt art) {

        if (previewing.containsKey(player)) {
            stopPreviewing(player);
        }
        ItemStack item = art.getMapItem();
        MapPreview preview = new MapPreview(this, player);
        preview.runTaskLaterAsynchronously(this, 300);
        player.setItemInHand(item);
        previewing.put(player, preview);
    }

    public void stopPreviewing(Player player) {

        if (previewing.containsKey(player)) {
            player.setItemInHand(new ItemStack(Material.AIR));
            previewing.get(player).cancel();
            previewing.remove(player);
        }
    }

    public boolean isPreviewing(Player player) {
        return previewing.containsKey(player);
    }

    private class MapPreview extends BukkitRunnable {
        ArtMap plugin;
        Player player;

        MapPreview(ArtMap plugin, Player player) {
            this.plugin = plugin;
            this.player = player;
        }

        public void run() {
            stopPreviewing(player);
        }
    }

    public ArtistHandler getArtistHandler() {
        return artistHandler;
    }

    public void setArtistHandler(ArtistHandler artistHandler) {
        this.artistHandler = artistHandler;
    }

    public ConcurrentHashMap<Player, String> getNameQueue() {
        return nameQueue;
    }

    public ConcurrentHashMap<Location, Easel> getEasels() {
        return easels;
    }

    public List<String> getTitleFilter() {
        return titleFilter;
    }

    public PixelTable getPixelTable() {
        return pixelTable;
    }

    public int getBackgroundID() {
        return backgroundID;
    }
}