package vspike;
/** Representative LB caller: casts an MC object to the added interface and calls it. After conversion this
 *  must become LBHooks.lb$getX(o) (sidecar static) since the interface is dropped from the target. */
public class TestCaller {
    public static int call(Object mc) { return ((TestAddition) mc).lb$getX(); }
}
