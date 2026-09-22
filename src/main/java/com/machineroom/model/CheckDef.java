package com.machineroom.model;

/** 三班檢查定義 (DEF_CHECK). */
public class CheckDef {
    public String id;
    public int seq;
    public String name;
    public String type;           // CheckType key
    public Shift shift;
    public String time = "";
    public boolean holidaySkip;
    public boolean enabled = true;
}
