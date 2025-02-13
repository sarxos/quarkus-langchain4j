package io.quarkiverse.langchain4j.deployment;

import static io.quarkiverse.langchain4j.deployment.Langchain4jDotNames.CHAT_MODEL;
import static io.quarkiverse.langchain4j.deployment.Langchain4jDotNames.EMBEDDING_MODEL;
import static io.quarkiverse.langchain4j.deployment.Langchain4jDotNames.IMAGE_MODEL;
import static io.quarkiverse.langchain4j.deployment.Langchain4jDotNames.MODEL_NAME;
import static io.quarkiverse.langchain4j.deployment.Langchain4jDotNames.MODERATION_MODEL;
import static io.quarkiverse.langchain4j.deployment.Langchain4jDotNames.STREAMING_CHAT_MODEL;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.langchain4j.deployment.config.LangChain4jBuildConfig;
import io.quarkiverse.langchain4j.deployment.items.ChatModelProviderCandidateBuildItem;
import io.quarkiverse.langchain4j.deployment.items.EmbeddingModelProviderCandidateBuildItem;
import io.quarkiverse.langchain4j.deployment.items.ImageModelProviderCandidateBuildItem;
import io.quarkiverse.langchain4j.deployment.items.InProcessEmbeddingBuildItem;
import io.quarkiverse.langchain4j.deployment.items.ModerationModelProviderCandidateBuildItem;
import io.quarkiverse.langchain4j.deployment.items.ProviderHolder;
import io.quarkiverse.langchain4j.deployment.items.SelectedChatModelProviderBuildItem;
import io.quarkiverse.langchain4j.deployment.items.SelectedEmbeddingModelCandidateBuildItem;
import io.quarkiverse.langchain4j.deployment.items.SelectedImageModelProviderBuildItem;
import io.quarkiverse.langchain4j.deployment.items.SelectedModerationModelProviderBuildItem;
import io.quarkiverse.langchain4j.runtime.Langchain4jRecorder;
import io.quarkiverse.langchain4j.runtime.NamedModelUtil;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;

public class BeansProcessor {

    private static final String FEATURE = "langchain4j";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void indexDependencies(BuildProducer<IndexDependencyBuildItem> producer) {
        producer.produce(new IndexDependencyBuildItem("dev.langchain4j", "langchain4j-core"));
    }

    @BuildStep
    public void handleProviders(BeanDiscoveryFinishedBuildItem beanDiscoveryFinished,
            List<ChatModelProviderCandidateBuildItem> chatCandidateItems,
            List<EmbeddingModelProviderCandidateBuildItem> embeddingCandidateItems,
            List<ModerationModelProviderCandidateBuildItem> moderationCandidateItems,
            List<ImageModelProviderCandidateBuildItem> imageCandidateItems,
            List<RequestChatModelBeanBuildItem> requestChatModelBeanItems,
            List<RequestModerationModelBeanBuildItem> requestModerationModelBeanBuildItems,
            LangChain4jBuildConfig buildConfig,
            BuildProducer<SelectedChatModelProviderBuildItem> selectedChatProducer,
            BuildProducer<SelectedEmbeddingModelCandidateBuildItem> selectedEmbeddingProducer,
            BuildProducer<SelectedModerationModelProviderBuildItem> selectedModerationProducer,
            BuildProducer<SelectedImageModelProviderBuildItem> selectedImageProducer,
            List<InProcessEmbeddingBuildItem> inProcessEmbeddingBuildItems) {

        Set<String> requestedChatModels = new HashSet<>();
        Set<String> requestedStreamingChatModels = new HashSet<>();
        Set<String> requestEmbeddingModels = new HashSet<>();
        Set<String> requestedModerationModels = new HashSet<>();
        Set<String> requestedImageModels = new HashSet<>();

        for (InjectionPointInfo ip : beanDiscoveryFinished.getInjectionPoints()) {
            DotName requiredName = ip.getRequiredType().name();
            String modelName = determineModelName(ip);
            if (CHAT_MODEL.equals(requiredName)) {
                requestedChatModels.add(modelName);
            } else if (STREAMING_CHAT_MODEL.equals(requiredName)) {
                requestedStreamingChatModels.add(modelName);
            } else if (EMBEDDING_MODEL.equals(requiredName)) {
                requestEmbeddingModels.add(modelName);
            } else if (MODERATION_MODEL.equals(requiredName)) {
                requestedModerationModels.add(modelName);
            } else if (IMAGE_MODEL.equals(requiredName)) {
                requestedImageModels.add(modelName);
            }
        }
        for (var bi : requestChatModelBeanItems) {
            requestedChatModels.add(bi.getModelName());
        }
        for (var bi : requestModerationModelBeanBuildItems) {
            requestedModerationModels.add(bi.getModelName());
        }

        if (!requestedChatModels.isEmpty() || !requestedStreamingChatModels.isEmpty()) {
            Set<String> allChatModelNames = new HashSet<>(requestedChatModels);
            allChatModelNames.addAll(requestedStreamingChatModels);
            for (String modelName : allChatModelNames) {
                Optional<String> userSelectedProvider;
                String configNamespace;
                if (NamedModelUtil.isDefault(modelName)) {
                    userSelectedProvider = buildConfig.defaultConfig().chatModel().provider();
                    configNamespace = "chat-model";
                } else {
                    if (buildConfig.namedConfig().containsKey(modelName)) {
                        userSelectedProvider = buildConfig.namedConfig().get(modelName).chatModel().provider();
                    } else {
                        userSelectedProvider = Optional.empty();
                    }
                    configNamespace = modelName + ".chat-model";
                }

                selectedChatProducer.produce(
                        new SelectedChatModelProviderBuildItem(
                                selectProvider(
                                        chatCandidateItems,
                                        userSelectedProvider,
                                        "ChatLanguageModel or StreamingChatLanguageModel",
                                        configNamespace),
                                modelName));
            }

        }

        for (String modelName : requestEmbeddingModels) {
            Optional<String> userSelectedProvider;
            String configNamespace;
            if (NamedModelUtil.isDefault(modelName)) {
                userSelectedProvider = buildConfig.defaultConfig().embeddingModel().provider();
                configNamespace = "embedding-model";
            } else {
                if (buildConfig.namedConfig().containsKey(modelName)) {
                    userSelectedProvider = buildConfig.namedConfig().get(modelName).embeddingModel().provider();
                } else {
                    userSelectedProvider = Optional.empty();
                }
                configNamespace = modelName + ".embedding-model";
            }

            selectedEmbeddingProducer.produce(
                    new SelectedEmbeddingModelCandidateBuildItem(
                            selectEmbeddingModelProvider(
                                    inProcessEmbeddingBuildItems,
                                    embeddingCandidateItems,
                                    userSelectedProvider,
                                    "EmbeddingModel",
                                    configNamespace),
                            modelName));
        }

        for (String modelName : requestedModerationModels) {
            Optional<String> userSelectedProvider;
            String configNamespace;
            if (NamedModelUtil.isDefault(modelName)) {
                userSelectedProvider = buildConfig.defaultConfig().moderationModel().provider();
                configNamespace = "moderation-model";
            } else {
                if (buildConfig.namedConfig().containsKey(modelName)) {
                    userSelectedProvider = buildConfig.namedConfig().get(modelName).moderationModel().provider();
                } else {
                    userSelectedProvider = Optional.empty();
                }
                configNamespace = modelName + ".moderation-model";
            }

            selectedModerationProducer.produce(
                    new SelectedModerationModelProviderBuildItem(
                            selectProvider(
                                    moderationCandidateItems,
                                    userSelectedProvider,
                                    "ModerationModel",
                                    configNamespace),
                            modelName));
        }

        for (String modelName : requestedImageModels) {
            Optional<String> userSelectedProvider;
            String configNamespace;
            if (NamedModelUtil.isDefault(modelName)) {
                userSelectedProvider = buildConfig.defaultConfig().imageModel().provider();
                configNamespace = "image-model";
            } else {
                if (buildConfig.namedConfig().containsKey(modelName)) {
                    userSelectedProvider = buildConfig.namedConfig().get(modelName).imageModel().provider();
                } else {
                    userSelectedProvider = Optional.empty();
                }
                configNamespace = modelName + ".image-model";
            }

            selectedImageProducer.produce(
                    new SelectedImageModelProviderBuildItem(
                            selectProvider(
                                    imageCandidateItems,
                                    userSelectedProvider,
                                    "ImageModel",
                                    configNamespace),
                            modelName));
        }

    }

    private String determineModelName(InjectionPointInfo ip) {
        AnnotationInstance modelNameInstance = ip.getRequiredQualifier(MODEL_NAME);
        if (modelNameInstance != null) {
            String value = modelNameInstance.value().asString();
            if ((value != null) && !value.isEmpty()) {
                return value;
            }
        }
        return NamedModelUtil.DEFAULT_NAME;
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private <T extends ProviderHolder> String selectProvider(
            List<T> candidateItems,
            Optional<String> userSelectedProvider,
            String beanType,
            String configNamespace) {
        List<String> availableProviders = candidateItems.stream().map(ProviderHolder::getProvider)
                .collect(Collectors.toList());
        if (availableProviders.isEmpty()) {
            throw new ConfigurationException(String.format(
                    "A %s bean was requested, but no langchain4j providers were configured. Consider adding an extension like 'quarkus-langchain4j-openai'",
                    beanType));
        }
        if (availableProviders.size() == 1) {
            // user has selected a provider, but it's not the one that is available
            if (userSelectedProvider.isPresent() && !availableProviders.get(0).equals(userSelectedProvider.get())) {
                throw new ConfigurationException(String.format(
                        "A %s bean with provider=%s was requested was requested via configuration, but the only provider found on the classpath is %s.",
                        beanType, userSelectedProvider.get(), availableProviders.get(0)));
            }
            return availableProviders.get(0);
        }
        // multiple providers exist, so we now need the configuration to select the proper one
        if (userSelectedProvider.isEmpty()) {
            throw new ConfigurationException(String.format(
                    "A %s bean was requested, but since there are multiple available providers, the 'quarkus.langchain4j.%s.provider' needs to be set to one of the available options (%s).",
                    beanType, configNamespace, String.join(",", availableProviders)));
        }
        boolean matches = availableProviders.stream().anyMatch(ap -> ap.equals(userSelectedProvider.get()));
        if (matches) {
            return userSelectedProvider.get();
        }
        throw new ConfigurationException(String.format(
                "A %s bean was requested, but the value of 'quarkus.langchain4j.%s.provider' does not match any of the available options (%s).",
                beanType, configNamespace, String.join(",", availableProviders)));
    }

    private <T extends ProviderHolder> String selectEmbeddingModelProvider(
            List<InProcessEmbeddingBuildItem> inProcessEmbeddingBuildItems,
            List<T> chatCandidateItems,
            Optional<String> userSelectedProvider,
            String requestedBeanName,
            String configNamespace) {
        List<String> availableProviders = chatCandidateItems.stream().map(ProviderHolder::getProvider)
                .collect(Collectors.toList());
        availableProviders.addAll(inProcessEmbeddingBuildItems.stream().map(InProcessEmbeddingBuildItem::getProvider)
                .toList());
        if (availableProviders.isEmpty()) {
            throw new ConfigurationException(String.format(
                    "A %s bean was requested, but no langchain4j providers were configured and no in-process embedding model were found on the classpath. "
                            +
                            "Consider adding an extension like 'quarkus-langchain4j-openai' or one of the in-process embedding models.",
                    requestedBeanName));
        }
        if (availableProviders.size() == 1) {
            // user has selected a provider, but it's not the one that is available
            if (userSelectedProvider.isPresent() && !availableProviders.get(0).equals(userSelectedProvider.get())) {
                throw new ConfigurationException(String.format(
                        "Embedding model provider %s was requested via configuration, but the only provider found on the classpath is %s.",
                        userSelectedProvider.get(), availableProviders.get(0)));
            }
            return availableProviders.get(0);
        }
        // multiple providers exist, so we now need the configuration to select the proper one
        if (userSelectedProvider.isEmpty()) {
            throw new ConfigurationException(String.format(
                    "A %s bean was requested, but since there are multiple available providers, the 'quarkus.langchain4j.%s.provider' needs to be set to one of the available options (%s).",
                    requestedBeanName, configNamespace, String.join(",", availableProviders)));
        }
        boolean matches = availableProviders.stream().anyMatch(ap -> ap.equals(userSelectedProvider.get()));
        if (matches) {
            return userSelectedProvider.get();
        }
        throw new ConfigurationException(String.format(
                "A %s bean was requested, but the value of 'quarkus.langchain4j.%s.provider' does not match any of the available options (%s).",
                requestedBeanName, configNamespace, String.join(",", availableProviders)));
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    public void cleanUp(Langchain4jRecorder recorder, ShutdownContextBuildItem shutdown) {
        recorder.cleanUp(shutdown);
    }

    @BuildStep
    public void unremoveableBeans(BuildProducer<UnremovableBeanBuildItem> unremoveableProducer) {
        unremoveableProducer.produce(UnremovableBeanBuildItem.beanTypes(ObjectMapper.class));
    }

}
