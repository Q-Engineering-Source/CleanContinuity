package me.pepperbell.continuity.client;

import me.pepperbell.continuity.api.client.CtmLoader;
import me.pepperbell.continuity.api.client.CtmLoaderRegistry;
import me.pepperbell.continuity.api.client.CachingPredicates;
import me.pepperbell.continuity.api.client.CtmProperties;
import me.pepperbell.continuity.api.client.QuadProcessor;
import me.pepperbell.continuity.client.processor.BaseCachingPredicates;
import me.pepperbell.continuity.client.processor.ProcessingDataKeys;
import me.pepperbell.continuity.client.processor.TopQuadProcessor;
import me.pepperbell.continuity.client.processor.overlay.StandardOverlayQuadProcessor;
import me.pepperbell.continuity.client.processor.simple.CtmSpriteProvider;
import me.pepperbell.continuity.client.processor.simple.FixedSpriteProvider;
import me.pepperbell.continuity.client.processor.simple.HorizontalSpriteProvider;
import me.pepperbell.continuity.client.processor.simple.RandomSpriteProvider;
import me.pepperbell.continuity.client.processor.simple.RepeatSpriteProvider;
import me.pepperbell.continuity.client.processor.simple.SimpleQuadProcessor;
import me.pepperbell.continuity.client.processor.simple.VerticalSpriteProvider;
import me.pepperbell.continuity.client.properties.BaseCtmProperties;
import me.pepperbell.continuity.client.properties.ConnectingCtmProperties;
import me.pepperbell.continuity.client.properties.OrientedConnectingCtmProperties;
import me.pepperbell.continuity.client.properties.RandomCtmProperties;
import me.pepperbell.continuity.client.properties.RepeatCtmProperties;
import me.pepperbell.continuity.client.properties.overlay.StandardOverlayCtmProperties;
import me.pepperbell.continuity.client.properties.TileAmountValidator;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ContinuityClient {
	public static final String ID = "continuity";
	public static final String NAME = "Continuity";
	public static final Logger LOGGER = LogManager.getLogger(NAME);

	private ContinuityClient() {
	}

	public static ResourceLocation asId(String path) {
		return new ResourceLocation(ID, path);
	}

	public static void registerLoaders() {
		ProcessingDataKeys.init();

		CtmLoaderRegistry registry = CtmLoaderRegistry.get();

		CtmLoader<OrientedConnectingCtmProperties> ctmLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<OrientedConnectingCtmProperties> getPropertiesFactory() {
				return TileAmountValidator.wrapFactory(BaseCtmProperties.wrapFactory(OrientedConnectingCtmProperties::new), new TileAmountValidator.AtLeast<>(47));
			}

			@Override
			public QuadProcessor.Factory<OrientedConnectingCtmProperties> getProcessorFactory() {
				return new SimpleQuadProcessor.Factory<>(new CtmSpriteProvider.Factory());
			}

			@Override
			public CachingPredicates.Factory<OrientedConnectingCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("ctm", ctmLoader);
		registry.registerLoader("glass", ctmLoader);

		CtmLoader<OrientedConnectingCtmProperties> horizontalLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<OrientedConnectingCtmProperties> getPropertiesFactory() {
				return TileAmountValidator.wrapFactory(BaseCtmProperties.wrapFactory(OrientedConnectingCtmProperties::new), new TileAmountValidator.Exactly<>(4));
			}

			@Override
			public QuadProcessor.Factory<OrientedConnectingCtmProperties> getProcessorFactory() {
				return new SimpleQuadProcessor.Factory<>(new HorizontalSpriteProvider.Factory());
			}

			@Override
			public CachingPredicates.Factory<OrientedConnectingCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("horizontal", horizontalLoader);
		registry.registerLoader("bookshelf", horizontalLoader);

		CtmLoader<OrientedConnectingCtmProperties> verticalLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<OrientedConnectingCtmProperties> getPropertiesFactory() {
				return TileAmountValidator.wrapFactory(BaseCtmProperties.wrapFactory(OrientedConnectingCtmProperties::new), new TileAmountValidator.Exactly<>(4));
			}

			@Override
			public QuadProcessor.Factory<OrientedConnectingCtmProperties> getProcessorFactory() {
				return new SimpleQuadProcessor.Factory<>(new VerticalSpriteProvider.Factory());
			}

			@Override
			public CachingPredicates.Factory<OrientedConnectingCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("vertical", verticalLoader);

		CtmLoader<ConnectingCtmProperties> topLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<ConnectingCtmProperties> getPropertiesFactory() {
				return TileAmountValidator.wrapFactory(BaseCtmProperties.wrapFactory(ConnectingCtmProperties::new), new TileAmountValidator.Exactly<>(1));
			}

			@Override
			public QuadProcessor.Factory<ConnectingCtmProperties> getProcessorFactory() {
				return new TopQuadProcessor.Factory();
			}

			@Override
			public CachingPredicates.Factory<ConnectingCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("top", topLoader);

		CtmLoader<BaseCtmProperties> fixedLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<BaseCtmProperties> getPropertiesFactory() {
				return BaseCtmProperties::new;
			}

			@Override
			public QuadProcessor.Factory<BaseCtmProperties> getProcessorFactory() {
				return new SimpleQuadProcessor.Factory<>(new FixedSpriteProvider.Factory());
			}

			@Override
			public CachingPredicates.Factory<BaseCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("fixed", fixedLoader);

		CtmLoader<RandomCtmProperties> randomLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<RandomCtmProperties> getPropertiesFactory() {
				// Must go through wrapFactory so init() parses and validates tiles (like all
				// other loaders); a bare constructor leaves spriteIds empty.
				return BaseCtmProperties.wrapFactory(RandomCtmProperties::new);
			}

			@Override
			public QuadProcessor.Factory<RandomCtmProperties> getProcessorFactory() {
				return new SimpleQuadProcessor.Factory<>(new RandomSpriteProvider.Factory());
			}

			@Override
			public CachingPredicates.Factory<RandomCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("random", randomLoader);

		CtmLoader<RepeatCtmProperties> repeatLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<RepeatCtmProperties> getPropertiesFactory() {
				return TileAmountValidator.wrapFactory(BaseCtmProperties.wrapFactory(RepeatCtmProperties::new), new RepeatCtmProperties.Validator<>());
			}

			@Override
			public QuadProcessor.Factory<RepeatCtmProperties> getProcessorFactory() {
				return new SimpleQuadProcessor.Factory<>(new RepeatSpriteProvider.Factory());
			}

			@Override
			public CachingPredicates.Factory<RepeatCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("repeat", repeatLoader);

		CtmLoader<StandardOverlayCtmProperties> overlayLoader = new CtmLoader<>() {
			@Override
			public CtmProperties.Factory<StandardOverlayCtmProperties> getPropertiesFactory() {
				return TileAmountValidator.wrapFactory(
						BaseCtmProperties.wrapFactory(StandardOverlayCtmProperties::new),
						new TileAmountValidator.AtLeast<>(17));
			}

			@Override
			public QuadProcessor.Factory<StandardOverlayCtmProperties> getProcessorFactory() {
				return new StandardOverlayQuadProcessor.Factory();
			}

			@Override
			public CachingPredicates.Factory<StandardOverlayCtmProperties> getPredicatesFactory() {
				return new BaseCachingPredicates.Factory<>(true);
			}
		};
		registry.registerLoader("overlay", overlayLoader);
	}
}
