import com.srctool.sinew.testing.FakeKeyValueStore

// Resolving sinew-testing and using one of its fakes is the whole check: it is published like every other module.
class UsesTesting {
    val prefs = FakeKeyValueStore()
}
