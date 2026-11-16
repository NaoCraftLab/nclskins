package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import java.util.*;

public final class BakedSurfacePresenterFixture {
    public record Case(String role, ViewSpec view, ViewSpec.Preview preview) {}

    public static ClientRuntime coldMenu(ClientOperations operations,PreviewModelFitStore store,TextureRegistry.TextureHandle handle) {
        return new ClientRuntime(new DefaultRuntimeServices(store),operations,new ClientExecutor() {
            public boolean isClientThread(){return true;}public void execute(Runnable action){action.run();}
        },()->java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()),action->{throw new AssertionError("fresh menu worker demand");},UiMessage::key,
                Optional.of(()->new CurrentPlayerAppearanceSource.PlayerAppearance(handle,SkinModel.CLASSIC,Optional.empty())),Optional.empty(),Optional.of(new OuterLayerVisibilityController() {
                    public OuterLayerVisibility current(){return OuterLayerVisibility.allVisible();}
                    public void applyDurable(OuterLayerVisibility value){throw new AssertionError("fresh menu durable write");}
                }),Optional.empty(),com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
    }

    public static SkinCatalogSource catalog(List<byte[]> content) {
        return new SkinCatalogSource() {
            public byte[] load(String collection,String skin,SkinModel model) {
                if(collection.equals(MinecraftSkinCatalog.COLLECTION_ID))return content.get(model==SkinModel.CLASSIC?0:1).clone();
                int index=Integer.parseInt(skin.substring(1));
                if(!List.of("card_resource","card_readonly","card_vanilla").contains(collection))throw new AssertionError("foreign catalog loader");
                return content.get(index).clone();
            }
            public List<CollectionDescriptor> collections() {
                var result=new ArrayList<CollectionDescriptor>();
                var names=List.of("card_resource","card_readonly","card_vanilla");
                var orders=List.of(CatalogCollectionOrder.resourcePack("fixture-pack",0),CatalogCollectionOrder.unspecified(),CatalogCollectionOrder.vanilla("minecraft"));
                for(int owner=0;owner<3;owner++) {
                    var skins=new ArrayList<SkinDescriptor>();
                    for(int i=0;i<content.size();i++)skins.add(new SkinDescriptor("s"+i,"Owned "+i,Optional.empty(),Optional.empty(),List.of(SkinModel.CLASSIC,SkinModel.SLIM)));
                    result.add(new CollectionDescriptor(names.get(owner),CatalogText.literal(names.get(owner)),Optional.empty(),Optional.empty(),skins,orders.get(owner)));
                }
                return result;
            }
        };
    }

    public static List<Case> joined(GalleryNativePassFixture subject, java.nio.file.Path importRoot) throws Exception {
        var result=new ArrayList<Case>();
        var collections=subject.cardCollections();
        var ids=List.of(PersonalSkinCatalog.COLLECTION_ID,"card_resource","card_readonly","card_vanilla");
        var roles=List.of("personal","resource","read-only","skin-Catalog");
        for(int i=0;i<ids.size();i++) {
            String id=ids.get(i);
            var collection=collections.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();
            var model=AddSourceModel.open(AccountUiPreferences.defaults(new UUID(0,801)),List.of(collection),SkinVariant.CLASSIC,UiMessage::key);
            var view=new AddSourcePresenter().present(model,false,854,480);
            for(var preview:view.previews())result.add(new Case(roles.get(i),view,preview));
        }
        var gallery=subject.runtime.view(854,480,0,0);
        var imported=new ExternalImportPresenter().present(subject.cardImport(importRoot),false,Optional.empty(),854,480);
        for(var preview:imported.previews())result.add(new Case("Import-Review",imported,preview));
        for(var preview:gallery.previews())if(!ViewHostPolicy.screenOwnedBody(gallery.screenId(),preview))result.add(new Case("right-Gallery",gallery,preview));
        return List.copyOf(result);
    }

    public static Case transition(GalleryNativePassFixture subject,int index) throws Exception {
        var collection=subject.cardCollections().stream().filter(c->c.id().equals("card_resource")).findFirst().orElseThrow();
        var model=AddSourceModel.open(AccountUiPreferences.defaults(new UUID(0,801)),List.of(collection),SkinVariant.CLASSIC,UiMessage::key).withQuery("Owned "+index);
        var view=new AddSourcePresenter().present(model,false,854,480);
        if(view.previews().size()!=1)throw new AssertionError("real source query must select one card");
        return new Case("resource",view,view.previews().get(0));
    }

    public static List<Case> cards(ClientRuntime runtime) {
        var cases = new ArrayList<Case>();
        var orders = List.of(CatalogCollectionOrder.personal(PersonalSkinCatalog.SOURCE_ID),
                CatalogCollectionOrder.resourcePack("fixture-pack", 0), CatalogCollectionOrder.unspecified(),
                CatalogCollectionOrder.vanilla("minecraft"));
        var roles = List.of("personal", "resource", "read-only", "skin-Catalog");
        for (int index = 0; index < orders.size(); index++) {
            String id = index == 0 ? PersonalSkinCatalog.COLLECTION_ID : "fixture" + index;
            var skin = new SkinCatalogSource.SkinDescriptor("a".repeat(64), "Owned source C",
                    Optional.empty(), Optional.empty(), List.of(SkinModel.CLASSIC, SkinModel.SLIM));
            var collection = new SkinCatalogSource.CollectionDescriptor(id, CatalogText.literal("Collection"),
                    Optional.empty(), Optional.empty(), List.of(skin), orders.get(index));
            var model = AddSourceModel.open(AccountUiPreferences.defaults(new UUID(0, 801)),
                    List.of(collection), SkinVariant.CLASSIC, UiMessage::key);
            var view = new AddSourcePresenter().present(model, false, 854, 480);
            cases.add(new Case(roles.get(index), view, view.previews().stream().findFirst().orElseThrow()));
        }
        var review = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withReview(new ClientOperations.ExternalImportReview(ExternalImportSource.SKIN_SHUFFLE,
                        List.of(new ClientOperations.ExternalImportCandidate("candidate-c", "Source C",
                                SkinVariant.CLASSIC, PersonalSkinSource.FILE, new byte[]{1, 2, 3},
                                "b".repeat(64), null, 0, false)), 1, 0));
        var imported = new ExternalImportPresenter().present(review, false, Optional.empty(), 854, 480);
        cases.add(new Case("Import-Review", imported, imported.previews().stream().findFirst().orElseThrow()));
        var gallery = runtime.view(854, 480, 0, 0);
        cases.add(new Case("right-Gallery", gallery, gallery.previews().stream()
                .filter(preview -> !ViewHostPolicy.screenOwnedBody(gallery.screenId(), preview)).findFirst().orElseThrow()));
        return List.copyOf(cases);
    }

    public static void verify(Case value) {
        var preview = value.preview();
        check(preview.intent() == PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL, "actual baked card intent " + value.role());
        check(preview.layoutScale() == 1 && preview.scale() == (value.role().equals("right-Gallery") ? 0.88F : 1.0F), "cards must not inherit Editor layout or logical zoom " + value.role());
        check(!ViewHostPolicy.screenOwnedBody(value.view().screenId(), preview), "cards have no full-body demand " + value.role());
        check(preview.bounds().equals(preview.anchorBounds()), "card slot is its own anchor " + value.role());
        check(value.view().clipFor(preview.id()).isPresent(), "actual card scroll clip " + value.role());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
