package vspike;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VSpikeGlobalProps implements IGlobalPropertyService {
    static final class Key implements IPropertyKey { final String s; Key(String s){this.s=s;}
        public boolean equals(Object o){return o instanceof Key && ((Key)o).s.equals(s);} public int hashCode(){return s.hashCode();} public String toString(){return s;} }
    // Process-wide Mixin property store; may be read/written from bootstrap and transform threads, so keep it concurrent.
    private final Map<String,Object> props = new ConcurrentHashMap<>();
    public IPropertyKey resolveKey(String name){ return new Key(name); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key){ return (T) props.get(key.toString()); }
    public void setProperty(IPropertyKey key, Object value){ if(value==null) props.remove(key.toString()); else props.put(key.toString(), value); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key, T defaultValue){ Object v=props.get(key.toString()); return v!=null?(T)v:defaultValue; }
    public String getPropertyString(IPropertyKey key, String defaultValue){ Object v=props.get(key.toString()); return v!=null?String.valueOf(v):defaultValue; }
}
