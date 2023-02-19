
BINDIR := bin

# https://stackoverflow.com/a/18258352
rwildcard = $(foreach d,$(wildcard $(1:=/*)),$(call rwildcard,$d,$2) $(filter $(subst *,%,$2),$d))

JAVA_CP := omz-proxy-all-latest.jar
JAVAC_FLAGS := -Xlint:all,-processing
JAVA_PATH_SEPARATOR := $(strip $(shell java -XshowSettings:properties 2>&1 | grep path.separator | cut -d '=' -f2))


.PHONY: all
all: basic-authentication cache compressor custom-headers mirror no-dns-root proxy-resources redirect-http server-timing virtual-host x-forwarded-for

.PHONY: basic-authentication
basic-authentication: $(BINDIR)/basic-authentication.jar
.PHONY: cache
cache: $(BINDIR)/cache.jar
.PHONY: compressor
compressor: $(BINDIR)/compressor.jar
.PHONY: custom-headers
custom-headers: $(BINDIR)/custom-headers.jar
.PHONY: mirror
mirror: $(BINDIR)/mirror.jar
.PHONY: no-dns-root
no-dns-root: $(BINDIR)/no-dns-root.jar
.PHONY: proxy-resources
proxy-resources: $(BINDIR)/proxy-resources.jar
.PHONY: redirect-http
redirect-http: $(BINDIR)/redirect-http.jar
.PHONY: server-timing
server-timing: $(BINDIR)/server-timing.jar
.PHONY: virtual-host
virtual-host: $(BINDIR)/virtual-host.jar
.PHONY: x-forwarded-for
x-forwarded-for: $(BINDIR)/x-forwarded-for.jar

.PHONY: clean
clean:
	rm -r $(BINDIR)/*

define pre_build
	@mkdir -p $(BINDIR)/$(1)
endef

define post_build
	@cp -r $(1)/main/resources/* $(BINDIR)/$(1)
	jar cf $(BINDIR)/$(1).jar -C $(BINDIR)/$(1) .
endef

$(BINDIR)/basic-authentication.jar: $(BINDIR)/virtual-host.jar $(call rwildcard,basic-authentication/main/java,*.java)
	$(call pre_build,basic-authentication)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/basic-authentication -cp "$(JAVA_CP)$(JAVA_PATH_SEPARATOR)$(BINDIR)/virtual-host.jar" $(filter %.java,$^)
	$(call post_build,basic-authentication)

$(BINDIR)/cache.jar: $(BINDIR)/virtual-host.jar $(call rwildcard,cache/main/java,*.java) $(call rwildcard,cache/main/scala,*.scala)
	$(call pre_build,cache)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/cache -cp "$(JAVA_CP)$(JAVA_PATH_SEPARATOR)$(BINDIR)/virtual-host.jar" $(filter %.java,$^)
	scalac -d $(BINDIR)/cache -cp "$(JAVA_CP)$(JAVA_PATH_SEPARATOR)$(BINDIR)/cache$(JAVA_PATH_SEPARATOR)$(BINDIR)/virtual-host.jar" -explain -deprecation $(filter %.scala,$^)
	$(call post_build,cache)

$(BINDIR)/compressor.jar: $(BINDIR)/cache.jar $(call rwildcard,compressor/main/java,*.java)
	$(call pre_build,compressor)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/compressor -cp "$(JAVA_CP)$(JAVA_PATH_SEPARATOR)$(BINDIR)/cache.jar" $(filter %.java,$^)
	$(call post_build,compressor)

$(BINDIR)/custom-headers.jar: $(BINDIR)/virtual-host.jar $(call rwildcard,custom-headers/main/java,*.java)
	$(call pre_build,custom-headers)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/custom-headers -cp "$(JAVA_CP)$(JAVA_PATH_SEPARATOR)$(BINDIR)/virtual-host.jar" $(filter %.java,$^)
	$(call post_build,custom-headers)

$(BINDIR)/mirror.jar: $(call rwildcard,mirror/main/java,*.java)
	$(call pre_build,mirror)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/mirror -cp "$(JAVA_CP)" $^
	$(call post_build,mirror)

$(BINDIR)/no-dns-root.jar: $(call rwildcard,no-dns-root/main/java,*.java)
	$(call pre_build,no-dns-root)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/no-dns-root -cp "$(JAVA_CP)" $^
	$(call post_build,no-dns-root)

$(BINDIR)/proxy-resources.jar: $(call rwildcard,proxy-resources/main/java,*.java)
	$(call pre_build,proxy-resources)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/proxy-resources -cp "$(JAVA_CP)" $^
	$(call post_build,proxy-resources)

$(BINDIR)/redirect-http.jar: $(call rwildcard,redirect-http/main/java,*.java)
	$(call pre_build,redirect-http)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/redirect-http -cp "$(JAVA_CP)" $^
	$(call post_build,redirect-http)

$(BINDIR)/server-timing.jar: $(call rwildcard,server-timing/main/java,*.java)
	$(call pre_build,server-timing)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/server-timing -cp "$(JAVA_CP)" $^
	$(call post_build,server-timing)

$(BINDIR)/virtual-host.jar: $(call rwildcard,virtual-host/main/java,*.java)
	$(call pre_build,virtual-host)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/virtual-host -cp "$(JAVA_CP)" $^
	$(call post_build,virtual-host)

$(BINDIR)/x-forwarded-for.jar: $(call rwildcard,x-forwarded-for/main/java,*.java)
	$(call pre_build,x-forwarded-for)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/x-forwarded-for -cp "$(JAVA_CP)" $^
	$(call post_build,x-forwarded-for)
