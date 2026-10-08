package dev.stmedrano.harbor.parent

class EnvironmentConfig private constructor(val liveNetworkingAllowed: Boolean) {
    companion object {
        fun read(values: Map<String, String>, ciFixture: Boolean): EnvironmentConfig {
            require(values["applicationId"] == "dev.stmedrano.harbor.parent") { "Wrong parent application" }
            require(values["firebasePackage"] == values["applicationId"]) { "Wrong Firebase client" }
            require(values["publishableKey"]?.matches(Regex("sb_publishable_[A-Za-z0-9_-]+")) == true) { "Use a publishable key" }
            val project = values["firebaseProjectId"]
            require(project?.matches(Regex("[a-z][a-z0-9-]{4,62}")) == true && project == values["firebaseClientProjectId"]) { "Firebase project mismatch" }
            if (ciFixture) {
                require(values["environment"] == "ci" && values["supabaseUrl"] == "https://parent-ci.invalid" && project == "harbor-parent-ci") { "Invalid CI fixture" }
            } else {
                require(values["environment"] == "development" && values["supabaseUrl"] == "https://bfvybxkjxilntjgndsrm.supabase.co" && project != "harbor-parent-ci") { "Unassigned environment" }
            }
            return EnvironmentConfig(!ciFixture)
        }
    }
}
