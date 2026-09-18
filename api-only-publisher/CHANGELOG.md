## [0.6.1](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.6.0...api-only-publisher-v0.6.1) (2026-09-18)


### 📝 Documentation

* **api-only-publisher:** update README version to 0.6.0 [skip ci] ([6d2bf92](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/6d2bf92ca686d523041fe72fd50e9c7f8bd36017))

# [0.6.0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.5.0...api-only-publisher-v0.6.0) (2026-09-18)


### ✨ New and updated features

* **api-only-transcriberj:** resolve descriptions through a project's ResourceBundle ([#21](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/21)) ([46a61c6](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/46a61c60fe56a60e29f5dc8d31f51c49b702a077)), closes [#21](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/21)


### 🐛 Bug Fixes

* **api-only-transcriberj:** generate a oneOf body the way its branches allow ([#25](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/25)) ([e1acbe0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/e1acbe0d3bce9c64ca5869ca0bb3c1586922e19f)), closes [#25](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/25)
* Integrate refreshVersions plugin for dependency management ([#22](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/22)) ([4f9b5b7](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/4f9b5b7a58cc6ef298cdc4c46855af3e728ed4a0)), closes [#22](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/22)
* **api-only-transcriberj:** resolve a description in the locale asked for, not the JVM's ([#23](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/23)) ([705b86b](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/705b86b249ae25b5fe5259d4b142dcb2dc9308ad)), closes [#23](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/23)


### 📝 Documentation

* **api-only-transcriberj:** add a guide to description bundles ([#24](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/24)) ([e4a6031](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/e4a60316e769618ff4874f1cb28e521863e28508)), closes [#24](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/24)
* **api-only-transcriberj:** add a guide to hand-written contract tests ([#27](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/27)) ([e569da7](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/e569da74285c458ae9935764f62ea1c671da9e67)), closes [#27](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/27) [#26](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/26)
* add a reference on authoring a specification library, and keep versions current ([#28](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/28)) ([b8bb842](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/b8bb842de22e545137f816ce0429822792e2fff4)), closes [#28](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/28)
* **api-only-transcriberj:** say where hand-written test code goes and what it owns ([#26](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/26)) ([ca36e43](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/ca36e4327094933d159c9a11fbf99468191c8729)), closes [#26](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/26) [#25](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/25)

# [0.5.0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.4.2...api-only-publisher-v0.5.0) (2026-09-18)


### ✨ New and updated features

* generate a class tree from a contract's AsyncAPI document, and stamp it in the Publisher ([#20](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/20)) ([0f19c57](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/0f19c5773b9a8016f19f6abf2d3b9a15ded477be)), closes [#20](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/20)
* **api-only-transcriberj:** generate the class tree on IDE sync ([#17](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/17)) ([f9e775d](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/f9e775d8982e8d9b122cc05f7d526b60b17e4a9d)), closes [#17](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/17)


### 🐛 Bug Fixes

* **api-only-transcriberj:** wire IDE sync from the root project, and never fail a subproject ([#18](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/18)) ([34e78d0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/34e78d0e2a6701fe05be6d95d76b474219231509)), closes [#18](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/18)


### 📝 Documentation

* add "Why API-Only" discussion document ([#19](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/19)) ([bc5b3e4](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/bc5b3e4510c18780e6972101c8f7c38abba1ae8a)), closes [#19](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/19)

## [0.4.2](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.4.1...api-only-publisher-v0.4.2) (2026-09-17)


### 👷 CI/CD

* stop a README-only change from releasing the component it documents ([#16](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/16)) ([a6740ac](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/a6740ac11df333034af93889cc62094b0f731f88)), closes [#16](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/16) [#14](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/14)


### 📝 Documentation

* add the migration from a checked-in document, and the Publisher failures the run-book lacked ([#15](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/15)) ([ff1ea56](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/ff1ea56ddfaa5856de44943af429bc323fe2ddef)), closes [#15](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/15) [#12](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/12) [#12](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/12) [#12](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/12) [#12](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/12)

## [0.4.1](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.4.0...api-only-publisher-v0.4.1) (2026-09-17)


### 📝 Documentation

* keep component versions in one place per document, and document the released versions ([#14](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/14)) ([d0cb5cb](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/d0cb5cb060cdf00ea8b7c8dfacb545002cc8eea7)), closes [#14](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/14)

# [0.4.0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.3.0...api-only-publisher-v0.4.0) (2026-09-17)


### ✨ New and updated features

* add the API-Only TranscriberJ, and stamp x-fragment-path in the Publisher ([#13](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/13)) ([3342117](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/334211768b43ddc47ca9a9fc533d4ae9bcb0445a)), closes [#13](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/13)


### 🐛 Bug Fixes

* document Publisher 0.3.0 and Subscriber 0.2.0 as released ([#11](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/11)) ([af54ca4](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/af54ca4576ec346f32b5003b27a783d732182a13)), closes [#11](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/11)


### 🔧 Misc

* add worktrees to .gitignore ([cf11626](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/cf116260ad46d09f1e214e17717b26c2f493f3dd))

# [0.3.0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.2.0...api-only-publisher-v0.3.0) (2026-09-13)


### ✨ New and updated features

* client subscriptions, a channel per subscription, and standalone use-case documentation ([#10](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/10)) ([48b4bce](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/48b4bcea411b1763f4926fe1525dd9d84e01d0c2)), closes [#10](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/10)

# [0.2.0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.1.0...api-only-publisher-v0.2.0) (2026-09-13)


### ✨ New and updated features

* **api-only-publisher:** fail lint on fragments no target reaches ([#9](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/9)) ([66fd17e](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/66fd17ef899de346ed6913ffb721751e81c60245)), closes [#9](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/9)

# [0.1.0](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.0.2...api-only-publisher-v0.1.0) (2026-09-13)


### ✨ New and updated features

* per-bundle contract versions and fetch-time archive checks ([#8](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/8)) ([82e5e37](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/82e5e375647f87cd8ee1fedb9bb4dc48faa475c3)), closes [#8](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/8)

## [0.0.2](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.0.1...api-only-publisher-v0.0.2) (2026-09-12)


### 🐛 Bug Fixes

* **ci:** authenticate the npm publish over OIDC, not a placeholder token ([#7](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/7)) ([3475a2d](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/3475a2d3393541d9959987c6f4874c462f489605)), closes [#7](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/7)

## [0.0.1](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/compare/api-only-publisher-v0.0.0...api-only-publisher-v0.0.1) (2026-09-12)


### 🐛 Bug Fixes

* align the Subscriber plugin with the conventions of its siblings ([#5](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/5)) ([4b0ca9b](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/4b0ca9b1e5aab0db45b1e8eca886187fd4935c27)), closes [#5](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/5)
* **ci:** grant the security scan the scope it needs to start at all ([#3](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/3)) ([2b41a80](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/2b41a8061d70f629b5b22db23f882dcfeec38649)), closes [#3](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/3)
* **ci:** publish to npm from its own job, over trusted publishing ([#6](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/6)) ([d208467](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/d208467abafa0fc1efa12b6a250883fda153a3f5)), closes [#6](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/6)
* ship the licence with both published artifacts ([#2](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/2)) ([f21ecc7](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/f21ecc7f6577c3e94bf2844c943688cba2964fb8)), closes [#2](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/2)


### 👷 CI/CD

* scan dependencies weekly, not on every release ([#4](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/4)) ([8208b3c](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/commit/8208b3c6d6cba5831dee8f71d0191d5d8cf1bd83)), closes [#4](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/issues/4)
