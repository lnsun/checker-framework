import checkers.nullness.quals.*;
import java.util.*;

class MyClass<@Nullable T> {
    T get() { throw new RuntimeException(); }
}

class OtherClass {

    void testPositive() {
        MyClass<@Nullable String> l = new MyClass<@Nullable String>();
        //:: error: (dereference.of.nullable)
        l.get().toString();
    }

    void testInvalidParam() {
        //:: error: (generic.argument.invalid)
        MyClass<@NonNull String> l;
    }

}
