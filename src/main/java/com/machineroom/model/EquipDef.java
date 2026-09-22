package com.machineroom.model;

import java.util.ArrayList;
import java.util.List;

/** 系統設備檢查定義 (DEF_EQUIP). */
public class EquipDef {
    public String id;
    public int seq;
    public String name;
    public String type;           // EquipType key
    public Shift shift;
    public String time = "";      // planned HH:mm or ""
    public List<String> statusOptions = new ArrayList<>();
    public boolean enabled = true;
}
