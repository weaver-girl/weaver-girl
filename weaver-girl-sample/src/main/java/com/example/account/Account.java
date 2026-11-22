package com.example.account;

/**
 * An account holder record that keeps a credential in a plain field.
 *
 * <p>It is the stand-in for an application a team already ships. The password is a field, the
 * class exposes it through the ordinary getter and setter its framework generated, and nothing
 * here knows about weaver-girl. That is the point: the field hooks in {@code account-guard} attach
 * to this class without it changing a line.
 *
 * <p>The field is written through the setter and read through the getter, which is the only shape
 * field interception can see — a direct {@code this.password = ...} in a constructor has no hook.
 */
public class Account {

  private String password;

  private final String owner;

  public Account(String owner, String password) {
    this.owner = owner;
    this.password = password;
  }

  public String getOwner() {
    return owner;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  /** What an endpoint or a log line would print. It reads the field through the getter. */
  public String describe() {
    return owner + "/" + getPassword();
  }
}
