// $Id$
/*
 * WorldGuard
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.worldguard.protection;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Representer;
import au.com.bytecode.opencsv.CSVReader;
import com.sk89q.util.config.ConfigurationNode;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.BlockVector2D;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldguard.protection.AreaFlags.State;
import com.sk89q.worldguard.protection.ProtectedRegion.CircularInheritanceException;
import com.sk89q.worldguard.domains.DefaultDomain;

public class YAMLDatabase implements ProtectionDatabase {
    private static Logger logger = Logger.getLogger("Minecraft.WorldGuard");
    
    /**
     * Settings for dumpign the file.
     */
    private DumperOptions options;
    /**
     * YAML handler.
     */
    private Yaml yaml;
    
    /**
     * References the YAML file.
     */
    private File file;
    /**
     * Holds the list of regions.
     */
    private Map<String,ProtectedRegion> regions;
    
    /**
     * Construct the database with a path to a file. No file is read or
     * written at this time.
     * 
     * @param file
     */
    public YAMLDatabase(File file) {
        this.file = file;

        options = new DumperOptions();
        options.setIndent(2);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);

        yaml = new Yaml(new SafeConstructor(), new Representer(), options);
    }
    
    @SuppressWarnings("unchecked")
    public void load() throws IOException {
        Map<String,ProtectedRegion> regions =
            new HashMap<String,ProtectedRegion>();
        Map<ProtectedRegion,String> parentSets =
                new LinkedHashMap<ProtectedRegion, String>();

        FileInputStream stream = new FileInputStream(file);
        Object res = yaml.load(new UnicodeReader(stream));
        
        if (!(res instanceof Map)) {
            logger.warning("Failed to read protection database from "
                    + file.getPath() + ": root note not a list");
            return;
        }
        
        for (Entry<String, Object> entry : ((Map<String, Object>)res).entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }

            Map<String, Object> item = (Map<String, Object>)entry.getValue();
            ConfigurationNode node = new ConfigurationNode(item);
            
            String id = entry.getKey();
            String type = node.getString("type", "");
            
            try {
                ProtectedRegion region;
                
                if (type.equals("cuboid")) {
                    Vector pt1 = readBlockVector(node.getProperty("pt1"));
                    Vector pt2 = readBlockVector(node.getProperty("pt2"));
                    BlockVector min = Vector.getMinimum(pt1, pt2).toBlockVector();
                    BlockVector max = Vector.getMaximum(pt1, pt2).toBlockVector();
                    
                    region = new ProtectedCuboidRegion(id, min, max);
                } else if (type.equals("polygon")) {
                    int minY = node.getInt("min-y", 0);
                    int maxY = node.getInt("max-y", 128);
                    List<BlockVector2D> points = new ArrayList<BlockVector2D>();
                    
                    for (Object d : node.getList("points")) {
                        points.add(readBlockVector2D(d));
                    }

                    region = new ProtectedPolygonalRegion(id, points, minY, maxY);
                } else {
                    logger.warning(file.getPath() + ": unknown region type: " + type);
                    continue;
                }
                
                String parentId = node.getString("parent");
                int priority = node.getInt("priority", 0);
                AreaFlags flags = readFlags(item, "flags");
                
                region.setPriority(priority);
                region.setFlags(flags);
                region.setOwners(readDomain(node, "owners"));
                region.setMembers(readDomain(node, "member"));
                region.setEnterMessage(node.getString("greeting"));
                region.setLeaveMessage(node.getString("farewell"));
                regions.put(id, region);
                
                // Link children to parents later
                if (parentId.length() > 0) {
                    parentSets.put(region, parentId);
                }
            } catch (IncompleteDataException e) {
                logger.warning(file.getPath() + ": bad area definition: " + id);
                continue;
            }
        }

        for (Map.Entry<ProtectedRegion, String> entry : parentSets.entrySet()) {
            ProtectedRegion parent = regions.get(entry.getValue());
            if (parent != null) {
                try {
                    entry.getKey().setParent(parent);
                } catch (CircularInheritanceException e) {
                    logger.warning("Circular inheritance detect with '"
                            + entry.getValue() + "' detected as a parent");
                }
            } else {
                logger.warning("Unknown region parent: " + entry.getValue());
            }
        }
        
        this.regions = regions;
    }

    @Override
    public void save() throws IOException {
        FileOutputStream stream = null;
        
        file.getParentFile().mkdirs();
        
        try {            
            stream = new FileOutputStream(file);
            
            Map<String, Object> out = new HashMap<String, Object>();
            
            for (Map.Entry<String, ProtectedRegion> entry : regions.entrySet()) {
                String id = entry.getKey();
                ProtectedRegion region = entry.getValue();
                Map<String, Object> item = new HashMap<String, Object>();

                // Depending on the region type, we have to write different data
                // as the regions are defined using different parameters
                if (region instanceof ProtectedCuboidRegion) {
                    ProtectedCuboidRegion cuboid = (ProtectedCuboidRegion)region;
                    item.put("pt1", writeBlockVector(cuboid.getMinimumPoint()));
                    item.put("pt2", writeBlockVector(cuboid.getMaximumPoint()));
                } else if (region instanceof ProtectedPolygonalRegion) {
                    ProtectedPolygonalRegion polygon = (ProtectedPolygonalRegion)region;
                    
                    List<Object> points = new ArrayList<Object>();
                    for (BlockVector2D point : polygon.getPoints()) {
                        points.add(writeBlockVector2D(point));
                    }
                    
                    item.put("points", points);
                }
                
                out.put(id, item);
            }
                
            yaml.dump(root, new OutputStreamWriter(stream, "UTF-8"));
            return true; 
        } catch (IOException e) {
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {
            }
        }
    }

    @Override
    public void load(RegionManager manager) throws IOException {
        load();
        manager.setRegions(regions);
    }

    @Override
    public void save(RegionManager manager) throws IOException {
        regions = manager.getRegions();
        save();
    }

    @Override
    public Map<String, ProtectedRegion> getRegions() {
        return regions;
    }

    @Override
    public void setRegions(Map<String, ProtectedRegion> regions) {
        this.regions = regions;
    }
    
    @SuppressWarnings("unchecked")
    private static BlockVector readBlockVector(Object obj)
            throws NumberFormatException, IncompleteDataException {
        if (obj == null) {
            throw new IncompleteDataException("vector expected, not defined");
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            if (list.size() != 3) {
                throw new IncompleteDataException(
                        "3d vector expected, list not 3 elements");
            }
            try {
                int x = (int) (Integer) list.get(0);
                int y = (int) (Integer) list.get(1);
                int z = (int) (Integer) list.get(2);
                return new BlockVector(x, y, z);
            } catch (ClassCastException e) {
                throw new IncompleteDataException(
                        "3d vector expected, expected 3 numbers");
            }
        } else {
            throw new IncompleteDataException("vector expected, not defined");
        }
    }
    
    @SuppressWarnings("unchecked")
    private static BlockVector2D readBlockVector2D(Object obj)
            throws NumberFormatException, IncompleteDataException {
        if (obj == null) {
            throw new IncompleteDataException("vector expected, not defined");
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            if (list.size() != 2) {
                throw new IncompleteDataException(
                        "2d vector expected, list not 2 elements");
            }
            try {
                int x = (int) (Integer) list.get(0);
                int z = (int) (Integer) list.get(1);
                return new BlockVector2D(x, z);
            } catch (ClassCastException e) {
                throw new IncompleteDataException(
                        "2d vector expected, expected 2 numbers");
            }
        } else {
            throw new IncompleteDataException("vector expected, not defined");
        }
    }
    
    public static int[] writeBlockVector(BlockVector vec) {
        return new int[] {vec.getBlockX(), vec.getBlockY(), vec.getBlockZ()};
    }
    
    public static int[] writeBlockVector2D(BlockVector2D vec) {
        return new int[] {vec.getBlockX(), vec.getBlockZ()};
    }
    
    @SuppressWarnings("unchecked")
    private static AreaFlags readFlags(Map<String, Object> map, String key)
            throws NumberFormatException, IncompleteDataException {
        Object obj = map.get(key);
        if (obj == null) {
            return new AreaFlags();
        } else if (obj instanceof List) {
            AreaFlags flags = new AreaFlags();

            List<Object> list = (List<Object>) obj;
            for (Object o : list) {
                String str = o.toString();
                
                if (str.length() == 2 && str.charAt(1) != '_') {
                    if (str.charAt(0) == '+') {
                        flags.set(str.substring(1, 2), State.ALLOW);
                    } else if (str.charAt(0) == '-') {
                        flags.set(str.substring(1, 2), State.DENY);
                    }
                } else if (str.length() == 3 && str.charAt(1) == '_') {
                    if (str.charAt(0) == '+') {
                        flags.set(str.substring(1, 3), State.ALLOW);
                    } else if (str.charAt(0) == '-') {
                        flags.set(str.substring(1, 3), State.DENY);
                    }
                }
            }
            
            return flags;
        } else {
            return new AreaFlags();
        }
    }
    
    private static DefaultDomain readDomain(ConfigurationNode parent, String key) {
        ConfigurationNode node = parent.getNode(key);
        if (node == null) {
            return new DefaultDomain();
        }
        
        DefaultDomain domain = new DefaultDomain();
        
        for (String owner : node.getStringList("owners", null)) {
            domain.addPlayer(owner);
        }
        
        for (String group : node.getStringList("groups", null)) {
            domain.addGroup(group);
        }
        
        return domain;
    }
    
    private static class IncompleteDataException extends Exception {
        private static final long serialVersionUID = 5550535033468474694L;
        
        public IncompleteDataException(String msg) {
            super(msg);
        }
    }
}
