/*
 * Copyright (C) 2014
 * University of Massachusetts
 * All Rights Reserved 
 * 
 * Initial developer(s): Westy.
 */
package edu.umass.cs.gns.commands;

import edu.umass.cs.gns.clientsupport.Defs;
import edu.umass.cs.gns.main.GNS;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.util.Set;
import java.util.TreeSet;

import static edu.umass.cs.gns.clientsupport.Defs.COMMANDNAME;
import static edu.umass.cs.gns.clientsupport.Defs.NEWLINE;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 *
 * @author westy
 */
public class CommandModule {

  private TreeSet<GnsCommand> commands;
  private String host;
  private boolean adminMode = false;

  public CommandModule() {
    initCommands();
  }

  private void initCommands() {
    this.commands = new TreeSet<GnsCommand>();
    addCommands(CommandDefs.getCommandDefs(), commands);
    GNS.getLogger().info(commands.size() + " commands added.");
  }

  /**
   * Add commands to this module. Commands instances are created by reflection
   * based on the command class names passed in parameter
   *
   * @param commandClasses a String[] containing the class names of the command
   * to instantiate
   * @param commands Set where the commands are added
   */
  protected void addCommands(String[] commandClasses, Set<GnsCommand> commands) {
    for (int i = 0; i < commandClasses.length; i++) {
      String commandClassName = commandClasses[i].trim();
      Class<?> clazz;
      try {
        clazz = Class.forName(commandClassName);
        Constructor<?> constructor;
        try {
          constructor = clazz.getConstructor(new Class[]{this.getClass()});
        } catch (NoSuchMethodException e) {
          constructor = clazz.getConstructor(new Class[]{CommandModule.class});
        }
        GnsCommand command = (GnsCommand) constructor.newInstance(new Object[]{this});
        GNS.getLogger().fine("Adding command " + (i + 1) + ": " + commandClassName + " with " + command.getCommandName() + ": " + command.getCommandParametersString());
        commands.add(command);
      } catch (Exception e) {
        GNS.getLogger().severe("Unable to add command for class " + commandClassName + ": " + e);
      }
    }
  }

  public GnsCommand lookupCommand(JSONObject json) {
    String action;
    try {
      action = json.getString(COMMANDNAME);
    } catch (JSONException e) {
      GNS.getLogger().warning("Unable find " + COMMANDNAME + " key in JSON command: " + e);
      return null;
    }
    GNS.getLogger().fine("Searching " + commands.size() + " commands:");
    // for now a linear search is fine
    for (GnsCommand command : commands) {
      GNS.getLogger().info("Search: " + command.toString());
      if (command.getCommandName().equals(action)) {
        GNS.getLogger().info("Found action: " + action);
        if (JSONContains(json, command.getCommandParameters())) {
          GNS.getLogger().info("Matched parameters: " + json);
          return command;
        }
      }
    }
    GNS.getLogger().warning("***COMMAND SEARCH***: Unable to find " + json);
    return null;
  }
  
  public enum CommandDescriptionFormat {
    HTML, TCP, TCP_Wiki
  }
  
  public static final String standardPreamble = "COMMAND PACKAGE: %s";
  
  public static final String wikiPreamble = "{| class=\"wikitable\"\n" +
"|+ Commands in %s\n" +
"! scope=\"col\" | Command Name\n" +
"! scope=\"col\" | Parameters\n" +
"! scope=\"col\" | Description";

  public String allCommandDescriptions(CommandDescriptionFormat format) {
    StringBuilder result = new StringBuilder();
    int cnt = 1;
    List<GnsCommand> commandList = new ArrayList(commands);
    // First sort by name
    Collections.sort(commandList, CommandNameComparator);
    // The sort them by package
    Collections.sort(commandList, CommandPackageComparator);
    String lastPackageName = null; 
    for (GnsCommand command : commandList) {
      String packageName = command.getClass().getPackage().getName();
      if (!packageName.equals(lastPackageName)) {
         if (format.equals(CommandDescriptionFormat.TCP_Wiki) && lastPackageName != null) {
          // finish last table
          result.append("|}");
        }
        lastPackageName = packageName;
        result.append(NEWLINE);
        result.append(String.format(format.equals(CommandDescriptionFormat.TCP_Wiki) ? wikiPreamble : standardPreamble, lastPackageName));
        result.append(NEWLINE);
      }
      //result.append(NEWLINE);
      //result.append(cnt++ + ": ");
      result.append(command.getUsage(format));
      result.append(NEWLINE);
      result.append(NEWLINE);
    }
    return result.toString();
  }

  private boolean JSONContains(JSONObject json, String[] parameters) {
    for (int i = 0; i < parameters.length; i++) {
      if (json.optString(parameters[i], null) == null) {
        return false;
      }
    }
    return true;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public boolean isAdminMode() {
    return adminMode;
  }

  public void setAdminMode(boolean adminMode) {
    this.adminMode = adminMode;
  }

  public static Comparator<GnsCommand> CommandPackageComparator
          = new Comparator<GnsCommand>() {

            @Override
            public int compare(GnsCommand command1, GnsCommand command2) {

              String packageName1 = command1.getClass().getPackage().getName();
              String packageName2 = command2.getClass().getPackage().getName();

              //ascending order
              return packageName1.compareTo(packageName2);

              //descending order
              //return fruitName2.compareTo(fruitName1);
            }

          };

  public static Comparator<GnsCommand> CommandNameComparator
          = new Comparator<GnsCommand>() {

            @Override
            public int compare(GnsCommand command1, GnsCommand command2) {

              String commandName1 = command1.getCommandName();
              String commandName2 = command2.getCommandName();

              //ascending order
              return commandName1.compareTo(commandName2);

              //descending order
              //return fruitName2.compareTo(fruitName1);
            }

          };
}
