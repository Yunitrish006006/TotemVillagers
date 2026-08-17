# Project Context

## Purpose

TotemVillagers is a planned standalone Totem module that makes villager trade
stock the result of observable, server-authoritative work.  It replaces free
restocking with real production while allowing both autonomous world work and
player-supplied workshop inputs.

## Intended Platform

- Fabric, Minecraft 26.2 and Java 25, aligned with the current Totem modules.
- TotemCore provides only stable shared APIs; villager AI, work orders,
  inventories and UI remain owned by TotemVillagers.

## Conventions

- The server is the only authority for work, stock, container consumption and
  trade completion.
- No work task may force-load chunks, bypass protection, duplicate an ItemStack
  or manufacture merchant stock without a recorded work action.
- Player-visible text and screens require English and Traditional-Chinese
  localisation.
- New gameplay changes require an approved OpenSpec proposal before code is
  implemented.
