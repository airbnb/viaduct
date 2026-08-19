package viaduct.java.grts;

import java.util.Map;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.types.GraphQLEnum;
import viaduct.java.api.types.GraphQLObject;

public final class GeneratedGRTs {
  private GeneratedGRTs() {}

  public static ObjectBase tag() {
    return new Tag();
  }

  public static ObjectBase item() {
    return new Item();
  }

  public static ObjectBase other() {
    return new Other();
  }

  public static ObjectBase stale() {
    return new Stale();
  }

  public static Object status() {
    return Status.ACTIVE;
  }
}

final class Tag extends ObjectBase implements GraphQLObject {
  Tag() {
    super(null, Map.of());
  }
}

final class Item extends ObjectBase implements GraphQLObject {
  Item() {
    super(null, Map.of());
  }
}

final class Other extends ObjectBase implements GraphQLObject {
  Other() {
    super(null, Map.of());
  }
}

final class Stale extends ObjectBase implements GraphQLObject {
  Stale() {
    super(null, Map.of());
  }
}

enum Status implements GraphQLEnum {
  ACTIVE
}
