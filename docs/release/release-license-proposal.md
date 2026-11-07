# Release license proposal

Draft for review. This document does not grant a license and is not a replacement for a root `LICENSE` file. No license has been applied to the project yet.

We want people to play, run servers, make videos, use modpacks and contribute fixes. We want approval before someone sells the mod or a fork.

| Use | Proposed permission |
| --- | --- |
| Play, inspect the source, modify it for personal use | Allowed |
| Share the original mod for free, with credit and the license | Allowed |
| Share a fork for free, clearly labeled as unofficial, with credit and the license | Allowed |
| Include the mod in a freely downloadable modpack | Allowed |
| Run a server, including paid access and server perks | Allowed to the extent Minecraft's own rules permit |
| Make videos, stream, or earn advertising/sponsorship income from those videos | Allowed |
| Accept optional donations that do not gate downloads or features | Allowed |
| Sell the mod, a fork, a paid download, or access to mod features | Written approval required |
| Sell a bundle that includes a copy of the mod or a fork | Written approval required |
| Claim a fork is an official NullSpace release | Not allowed |

Server fees and permitted server perks pay for hosted gameplay. They must not be used to sell downloads or licenses to the mod itself. A server's permission to charge does not grant permission to sell a modified mod or unlock features in a distributed client mod for money.

## Why MIT and GPL do not fit

Both permit commercial use and selling copies. MIT permits proprietary forks. GPL requires the corresponding source and GPL rights when distributing covered versions, but does not require approval to sell them. See the [MIT terms](https://choosealicense.com/licenses/mit/) and the [GNU GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html#DoesTheGPLAllowMoney).

An unmodified noncommercial license would also be broader than this proposal: the intended permissions explicitly include commercial server hosting and videos. A custom license needs to state those exceptions directly. A license that restricts selling copies should be described as source available, rather than open source; unrestricted commercial redistribution is part of the [Open Source Definition](https://opensource.org/osd).

## Proposed license text

**NullSpace Free Distribution License — draft 1**

Copyright (c) 2026 the NullSpace contributors.

Subject to the conditions below, the copyright holders grant permission to use, copy, inspect, modify and distribute the NullSpace software and original project assets, in source or compiled form.

1. You may distribute the software and modified versions without charging for those copies, their download, or a license to use them. Freely downloadable modpacks are permitted. You may accept voluntary donations if payment does not unlock copies or features of the distributed software. The server permissions in condition 3 are an exception for hosted gameplay.
2. Selling, licensing for a fee, placing behind a paywall, or including the software or a modified version in a paid software bundle requires prior written permission from the relevant copyright holders. The server and media permissions below are exceptions to this condition.
3. You may operate and monetize servers using the software, including charging for hosted gameplay and server perks to the extent permitted by Minecraft's applicable rules. This permission does not permit selling copies of the software, modified versions, or licenses to the software. You must comply with Minecraft's applicable terms and usage rules.
4. You may create, publish and monetize videos, streams, screenshots and other media featuring the software, including through advertising, sponsorships and paid subscriptions.
5. Distributed copies and modified versions must include this license and retain copyright and attribution notices. Modified versions must identify their changes and must not claim to be official NullSpace releases. Recipients must receive these same permissions and conditions for the NullSpace material. You retain ownership of your original contributions.
6. These permissions apply only to material the copyright holders are entitled to license. Third-party software and assets retain their own licenses. No rights to Minecraft, Mojang or Microsoft material or trademarks are granted. Permission to use the software does not imply endorsement or permission to use project branding to misrepresent a product as official.
7. The software is provided "as is", without warranty of any kind, express or implied. To the extent permitted by law, the copyright holders are not liable for claims, damages or other liability arising from the software or its use.
8. These permissions terminate if you violate these conditions. Rights already granted to recipients who comply with these conditions are not terminated solely by your violation. Separate written permission may grant additional rights.

All rights not expressly granted are reserved.

## Before applying this draft

Confirm the copyright notice and an actual contact for permission requests. Review contributor and third-party rights before granting permission for the whole repository. This is a project-specific draft, not a standard license or a legal opinion; have its wording reviewed before public release. Publishing under it would mean including the approved text in the root `LICENSE`, packaged JAR and release downloads, and declaring a matching custom license identifier in Fabric and Modrinth metadata.
