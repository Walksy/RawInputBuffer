<h1>Raw Input Buffer</h1>

This mod aims to reduce input lag, micro-stutters, and frame rate drops caused by Minecraft's default mouse handling. This is especially noticiable on polling rates above 1000 Hz, where frame rates can drop by 50-70% when moving the camera around, depending on the polling rate. With this mod installed you'll be able to use polling rates higher than 1000 Hz with no stutters.

My testing was done using a mouse with a polling rate of up to 8000 Hz.

---
### Solution:

This mod intercepts and replaces Minecraft's mouse handling. Instead of relying on GLFW, the mod communicates directly with the Windows Kernal and offloads mouse polling to a dedicated, asynchronous background thread using a hidden window class.
When the cursor is locked in game, standard legacy mouse data is no longer generated, freeing up CPU resources.

### Compatability:

**Windows OS only**: This mod relies on native Windows APIs and will automatically disable itself on other operating systems.

---
**Credits**

- Mod: Walksy

I created this mod around 4-5 weeks ago but noticed a similar concept was recently added to [Ixeris](https://modrinth.com/mod/ixeris) by [decce6](https://modrinth.com/user/decce6). While the implementations differ, both mods achieve similar results. I recommend you try them both out.
