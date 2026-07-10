import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class FB3 {
    static final class Obj { long f; }
    static long run(String name, int IT, int MASK, Obj[] o, java.util.function.LongUnaryOperator body){
        long s=0; for(int w=0;w<IT/2;w++) s+=body.applyAsLong(w); // warmup
        long t=System.nanoTime(); for(int i=0;i<IT;i++) s+=body.applyAsLong(i); long ns=System.nanoTime()-t;
        System.out.printf("  %-30s %5.2f ns/access%n", name, ns/(double)IT); return s;
    }
    public static void main(String[] a){
        int N=256, MASK=255, IT=100_000_000; Obj[] o=new Obj[N]; for(int i=0;i<N;i++)o[i]=new Obj();
        IdentityHashMap<Object,long[]> ihm=new IdentityHashMap<>(); ConcurrentHashMap<Object,long[]> chm=new ConcurrentHashMap<>();
        for(Obj x:o){ihm.put(x,new long[1]);chm.put(x,new long[1]);}
        long s=0;
        s+=run("real field (baseline)", IT, MASK, o, i->{Obj x=o[(int)i&MASK]; return ++x.f;});
        s+=run("unsync IdentityHashMap", IT, MASK, o, i->{long[] q=ihm.get(o[(int)i&MASK]); return ++q[0];});
        s+=run("ConcurrentHashMap", IT, MASK, o, i->{long[] q=chm.get(o[(int)i&MASK]); return ++q[0];});
        if(s==42)System.out.print("");
    }
}
