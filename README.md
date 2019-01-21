# TidyingChest

TidyingChest is a small plugin for [Spigot](https://www.spigotmc.org) Minecraft servers. It allows players to create chests which will automatically transfer their contents into other ones without to be connected with hopper and sorting item redstone system.

## How to install

There is no dependencies, simply drop the jar file into your plugin directory, then restart (or reload) your server. All configuration parameters are explained in this [config.yml](https://github.com/arboriginal/TidyingChest/blob/master/src/config.yml).

You can download the last release here: [TidyingChest.jar](https://github.com/arboriginal/TidyingChest/releases).

## How to use

Place a chest, then place a sign on one of its sides and type on the first line (by default) "[tc]" for a "root" chest (where place items) or "[tt]" for a "target" chest (where items will be sent). For root chests, that's all. For target chests, you have to hit the sign with the item you want it receives, or with your empty hand to create a "catch all" (will receive all items no target chests exists for them).

## Configuration

All parameters are described in this [config.yml](https://github.com/arboriginal/TidyingChest/blob/master/src/config.yml).

You can change wording for messages, and how the chests' sign are displayed, which words players have to type to create one, etc.


## Permissions

All permissions are listed with a short description in this [plugin.yml](https://github.com/arboriginal/TidyingChest/blob/master/src/plugin.yml).

You can define your own permissions for player's limitation by chest type. Refers to the [config.yml](https://github.com/arboriginal/TidyingChest/blob/master/src/config.yml).

## Commands

* **/tc-reload** will reload the configuration
