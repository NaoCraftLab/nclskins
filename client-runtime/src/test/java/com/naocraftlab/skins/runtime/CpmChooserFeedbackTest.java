package com.naocraftlab.skins.runtime;

import com.google.gson.JsonParser;
import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class CpmChooserFeedbackTest {
    @Test
    void shippedCopyAndLockedFilterKeepOnlyTheNecessaryExplanation() throws Exception {
        for (String locale : List.of("en_us", "ru_ru")) {
            Path file = Path.of("../compat/resources/canonical/src/main/resources/assets/nclskins/lang/" + locale + ".json");
            var language = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            TextResolver resolver = message -> language.has(message.key())
                    ? language.get(message.key()).getAsString() : message.key();
            assertEquals(locale.equals("ru_ru") ? "Эту модель CPM нельзя преобразовать в скин"
                    : "This CPM model cannot be converted to a skin",
                    language.get("nclskins.add_source.cpm.conversion_failed").getAsString());
            for (SkinModel type : SkinModel.values()) {
                AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID),
                        List.of(), SkinVariant.CLASSIC, resolver).withSelectedTab(AddSourceTab.CATALOG)
                        .withImportContext(new AddSourceModel.ImportContext(Optional.of(type),
                                Optional.of(CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED)));
                for (int[] size : List.of(new int[] {240, 240}, new int[] {180, 180},
                        new int[] {854, 160}, new int[] {320, 240})) {
                    ViewSpec view = new AddSourcePresenter().present(model, false, size[0], size[1]);
                    var filter = view.widget("add.catalog.filter").orElseThrow();
                    assertFalse(filter.enabled());
                    String copy = resolver.resolve(filter.hint().orElseThrow());
                    assertEquals(language.get("nclskins.add_source.cpm.required_"
                            + type.name().toLowerCase(java.util.Locale.ROOT)).getAsString(), copy);
                    assertFalse(copy.endsWith("."));
                    assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("add.catalog.reason.text")));
                    assertTrue(view.widget("add.catalog.reason").isEmpty());
                    assertTrue(view.navigationNode("add.catalog.reason").isEmpty());
                    var status = view.widget("add.catalog.status.info").orElseThrow();
                    assertEquals(filter.hint().orElseThrow().key(), status.label().key());
                    assertTrue(view.navigationNode(status.id()).orElseThrow().enabled());
                    assertTrue(view.clipFor(status.id()).isPresent());
                }
            }
        }
    }
}
