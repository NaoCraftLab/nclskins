package com.naocraftlab.skins.buildlogic.probe;

import com.tom.cpl.block.BlockState;
import com.tom.cpl.block.BlockStateHandler;
import com.tom.cpl.block.entity.EntityType;
import com.tom.cpl.block.entity.EntityTypeHandler;
import com.tom.cpl.item.Stack;
import com.tom.cpl.item.ItemStackHandler;
import com.tom.cpl.nbt.NBTTagCompound;
import java.util.List;

public final class CpmHostTags {
    public static final BlockStateHandler<Object> BLOCKS = new BlockStateHandler<>() {
        public String getBlockId(Object state) { return "fixture:air"; }
        public List<String> getBlockStates(Object state) { return List.of(); }
        public String getPropertyValue(Object state, String property) { return ""; }
        public int getPropertyValueInt(Object state, String property) { return 0; }
        public List<String> getAllValuesFor(Object state, String property) { return List.of(); }
        public List<String> listNativeTags() { return List.of(); }
        public boolean equals(Object a, Object b) { return a == b; }
        public boolean equalsFull(Object a, Object b) { return a == b; }
        public Stack getStackFromState(Object state) { return ITEMS.emptyObject(); }
        public boolean isInTag(String tag, Object state) { return false; }
        public List<String> listTags(Object state) { return List.of(); }
        public List<BlockState> listNativeEntries(String tag) { return List.of(); }
        public List<BlockState> getAllElements() { return List.of(); }
        public BlockState emptyObject() { return wrap(null); }
    };
    public static final ItemStackHandler<Object> ITEMS = new ItemStackHandler<>() {
        public List<String> listTags(Object stack) { return List.of(); }
        public List<String> listNativeTags() { return List.of(); }
        public int getCount(Object stack) { return 0; }
        public int getMaxCount(Object stack) { return 64; }
        public int getDamage(Object stack) { return 0; }
        public int getMaxDamage(Object stack) { return 0; }
        public boolean itemEquals(Object a, Object b) { return a == b; }
        public boolean itemEqualsFull(Object a, Object b) { return a == b; }
        public NBTTagCompound getTag(Object stack) { return new NBTTagCompound(); }
        public boolean isInTag(String tag, Object stack) { return false; }
        public String getItemId(Object stack) { return "fixture:air"; }
        public String getItemDisplayName(Object stack) { return "Fixture"; }
        public List<Stack> listNativeEntries(String tag) { return List.of(); }
        public List<Stack> getAllElements() { return List.of(); }
        public Stack emptyObject() { return wrap(null); }
    };
    public static final EntityTypeHandler<Object> ENTITIES = new EntityTypeHandler<>() {
        public boolean isInTag(String tag, Object state) { return false; }
        public List<String> listTags(Object state) { return List.of(); }
        public boolean equals(Object a, Object b) { return a == b; }
        public String getEntityId(Object state) { return "fixture:player"; }
        public List<String> listAllActiveEffectTypes() { return List.of(); }
        public List<String> listNativeTags() { return List.of(); }
        public List<EntityType> listNativeEntries(String tag) { return List.of(); }
        public List<EntityType> getAllElements() { return List.of(); }
        public EntityType emptyObject() { return wrap(null); }
    };
}
