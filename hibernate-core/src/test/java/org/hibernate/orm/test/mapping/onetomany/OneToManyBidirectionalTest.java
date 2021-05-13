/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.mapping.onetomany;

import java.util.ArrayList;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;

import org.hibernate.Hibernate;
import org.hibernate.boot.SessionFactoryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;

import org.hibernate.testing.jdbc.SQLStatementInterceptor;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.FailureExpected;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryProducer;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Andrea Boriero
 */
@DomainModel(
		annotatedClasses = {
				OneToManyBidirectionalTest.Order.class,
				OneToManyBidirectionalTest.Item.class
		}
)
@SessionFactory
@ServiceRegistry(
		settings = {
				@Setting(name = AvailableSettings.GENERATE_STATISTICS, value = "true"),
				@Setting(name = AvailableSettings.HBM2DDL_DATABASE_ACTION, value = "create-drop")
		}
)
public class OneToManyBidirectionalTest implements SessionFactoryProducer {

	private SQLStatementInterceptor sqlStatementInterceptor;

	public SessionFactoryImplementor produceSessionFactory(MetadataImplementor model) {
		final SessionFactoryBuilder sessionFactoryBuilder = model.getSessionFactoryBuilder();
		sqlStatementInterceptor = new SQLStatementInterceptor( sessionFactoryBuilder );
		return (SessionFactoryImplementor) sessionFactoryBuilder.build();
	}

	@BeforeEach
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			Item i = new Item( 1L );
			session.save( i );

			Item i2 = new Item( 2L );
			session.save( i2 );

			Order o = new Order( 3L );
			o.addItem( i );
			o.addItem( i2 );

			session.save( o );
		} );
	}

	@AfterEach
	public void tearDown(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			session.createQuery( "delete from Item" ).executeUpdate();
			session.createQuery( "delete from Order" ).executeUpdate();
		} );
	}

	@Test
	public void testFetchingSameAssociationTwice(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					Statistics statistics = session.getSessionFactory().getStatistics();
					statistics.clear();
					sqlStatementInterceptor.clear();

					List<Item> items = session.createQuery(
							"from Item i" +
									"    join fetch i.order o" +
									"    join fetch i.order o2", Item.class ).list();
					/*
						select i1_0.id, o21_0.id, o21_0.name
							from Item as i1_0
							inner join "Order" as o21_0 on i1_0."order_id" = o21_0.id
							inner join "Order" as o1_0  on i1_0."order_id" = o1_0.id
					 */

					assertJoinFrequencyInSQL( true, 2, 0 );
					sqlStatementInterceptor.clear();

					assertThat( items.size(), is( 2 ) );
					Order order = items.get( 0 ).getOrder();
					assertTrue( Hibernate.isInitialized( order ) );
					List<Item> lineItems = order.getLineItems();
					assertFalse( Hibernate.isInitialized( lineItems ) );

					assertThat( statistics.getPrepareStatementCount(), is( 1L ) );
					Item item = lineItems.get( 0 );

					/*
						select l1_0."order_id", l1_0.id
    					from Item as l1_0
   	 					where l1_0."order_id" = ?
					 */

					assertNoJoinInSQL( 0 );
					sqlStatementInterceptor.clear();

					Order itemOrder = item.getOrder();
					assertTrue( Hibernate.isInitialized( itemOrder ) );
					assertThat( itemOrder, is( itemOrder ) );
					assertTrue( Hibernate.isInitialized( itemOrder.getLineItems() ) );
					assertThat( statistics.getPrepareStatementCount(), is( 2L ) );

				}
		);
	}

	@Test
	public void testRetrievingItem(SessionFactoryScope scope) {
		scope.inTransaction(
				session -> {
					Statistics statistics = session.getSessionFactory().getStatistics();
					statistics.clear();
					sqlStatementInterceptor.clear();

					Item item = session.find( Item.class, 1L );

					/*
						select i1_0.id, o1_0.id, o1_0.name
							from Item as i1_0
							left outer join "Order" as o1_0  on i1_0."order_id" = o1_0.id
							where i1_0.id = ?
					 */

					assertJoinFrequencyInSQL( false, 1, 0 );
					sqlStatementInterceptor.clear();

					Order order = item.getOrder();
					assertTrue( Hibernate.isInitialized( order ) );
					assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

					assertThat( order.getLineItems().size(), is( 2 ) );

					/*
						select l1_0."order_id", l1_0.id
						from Item as l1_0
						where l1_0."order_id" = ?
					 */

					assertNoJoinInSQL( 0 );
					sqlStatementInterceptor.clear();

					assertThat( statistics.getPrepareStatementCount(), is( 2L ) );
					for ( Item itm : order.getLineItems() ) {
						assertThat( itm.getOrder(), is( order ) );
					}
					assertThat( statistics.getPrepareStatementCount(), is( 2L ) );

				} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Item> results = session.createQuery( "select i from Item i", Item.class ).list();
			/*
				1) select i1_0.id, i1_0."order_id"
    				from Item as i1_0
    			2) 	select o1_0.id, o1_0.name
    				from "Order" as o1_0
    				where o1_0.id = ?
			 */

			assertNoJoinInSQL( 0 );
			assertNoJoinInSQL( 1 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 ).getOrder();
			assertTrue( Hibernate.isInitialized( order ) );
			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );

			Assert.assertFalse( Hibernate.isInitialized( order.getLineItems() ) );

			assertThat( order.getLineItems().size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			assertThat( statistics.getPrepareStatementCount(), is( 3L ) );

			for ( Item itm : order.getLineItems() ) {
				assertThat( itm.getOrder(), is( order ) );
			}
			assertThat( statistics.getPrepareStatementCount(), is( 3L ) );
		} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Item> results = session.createQuery( "select i from Item i join i.order", Item.class ).list();
			/*
				1)	select i1_0.id, i1_0."order_id"
    				from Item as i1_0
    				inner join "Order" as o1_0 on i1_0."order_id" = o1_0.id
    			2) select o1_0.id, o1_0.name
    				from "Order" as o1_0
   					 where o1_0.id = ?
			 */

			assertJoinFrequencyInSQL( true, 1, 0 );
			assertNoJoinInSQL( 1 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 ).getOrder();
			assertTrue( Hibernate.isInitialized( order ) );
			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );

			Assert.assertFalse( Hibernate.isInitialized( order.getLineItems() ) );
			assertThat( order.getLineItems().size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			assertThat( statistics.getPrepareStatementCount(), is( 3L ) );

			for ( Item itm : order.getLineItems() ) {
				assertThat( itm.getOrder(), is( order ) );
			}
			assertThat( statistics.getPrepareStatementCount(), is( 3L ) );
		} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Item> results = session.createQuery( "select i from Item i join fetch i.order", Item.class ).list();

			/*
				select i1_0.id, o1_0.id, o1_0.name
    			from Item as i1_0
    			inner join "Order" as o1_0 on i1_0."order_id" = o1_0.id
			 */

			assertJoinFrequencyInSQL( true, 1, 0 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 ).getOrder();
			assertTrue( Hibernate.isInitialized( order ) );
			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

			Assert.assertFalse( Hibernate.isInitialized( order.getLineItems() ) );

			assertThat( order.getLineItems().size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );

			Assert.assertTrue( Hibernate.isInitialized( order.getLineItems() ) );

			for ( Item itm : order.getLineItems() ) {
				assertThat( itm.getOrder(), is( order ) );
			}
			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );
		} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Item> results = session.createQuery(
					"select i from Item i join i.order o join o.lineItems",
					Item.class
			).list();

			/*
				1)	select i1_0.id, i1_0."order_id"
    				from Item as i1_0
    				inner join "Order" as o1_0 on i1_0."order_id" = o1_0.id
   					inner join Item as l1_0  on l1_0."order_id" = o1_0.id
   				2)	select o1_0.id, o1_0.name
					from "Order" as o1_0
					where o1_0.id = ?
			 */

			assertJoinFrequencyInSQL( true, 2, 0 );
			assertNoJoinInSQL( 1 );
			sqlStatementInterceptor.clear();

			Item item = results.get( 0 );
			Order order = item.getOrder();
			assertTrue( Hibernate.isInitialized( order ) );
			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );

			Assert.assertFalse( Hibernate.isInitialized( order.getLineItems() ) );
			assertThat( order.getLineItems().size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			for ( Item itm : order.getLineItems() ) {
				assertThat( itm.getOrder(), is( order ) );
			}
			assertThat( statistics.getPrepareStatementCount(), is( 3L ) );
		} );
	}

	@Test
	public void testRetrievingOrder(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			Order order = session.find( Order.class, 3L );

			/*
				 select o1_0.id, o1_0.name
    			from "Order" as o1_0
    			where o1_0.id = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			List<Item> lineItems = order.getLineItems();
			assertFalse( Hibernate.isInitialized( lineItems ) );
			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

			assertThat( lineItems.size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			assertTrue( Hibernate.isInitialized( lineItems ) );
			for ( Item itm : lineItems ) {
				assertThat( itm.getOrder(), is( order ) );
			}

			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );
		} );


		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Order> results = session.createQuery( "select o from Order o", Order.class ).list();

			/*
				select o1_0.id, o1_0.name
    			from "Order" as o1_0
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 );
			List<Item> lineItems = order.getLineItems();
			assertFalse( Hibernate.isInitialized( lineItems ) );

			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

			assertThat( lineItems.size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			assertTrue( Hibernate.isInitialized( lineItems ) );
			for ( Item itm : lineItems ) {
				assertThat( itm.getOrder(), is( order ) );
			}

			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );
		} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Order> results = session.createQuery( "select o from Order o join o.lineItems", Order.class ).list();

			/*
				select o1_0.id, o1_0.name
    			from "Order" as o1_0
    			inner join Item as l1_0 on l1_0."order_id" = o1_0.id
			 */

			assertJoinFrequencyInSQL( true, 1, 0 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 );
			assertFalse( Hibernate.isInitialized( order.getLineItems() ) );
			List<Item> lineItems = order.getLineItems();
			assertFalse( Hibernate.isInitialized( lineItems ) );
			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

			assertThat( lineItems.size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			assertTrue( Hibernate.isInitialized( lineItems ) );

			for ( Item itm : lineItems ) {
				assertThat( itm.getOrder(), is( order ) );
			}
			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );
		} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Order> results = session.createQuery( "select o from Order o join fetch o.lineItems", Order.class )
					.list();

			/*
				select o1_0.id, l1_0."order_id", l1_0.id, o1_0.name
    			from "Order" as o1_0
    			inner join Item as l1_0 on l1_0."order_id" = o1_0.id
			 */

			assertJoinFrequencyInSQL( true, 1, 0 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 );
			List<Item> lineItems = order.getLineItems();
			assertTrue( Hibernate.isInitialized( lineItems ) );
			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

			assertThat( lineItems.size(), is( 2 ) );
			for ( Item itm : lineItems ) {
				assertThat( itm.getOrder(), is( order ) );
			}

			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );
		} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Order> results = session.createQuery(
					"select o from Order o join o.lineItems l join l.order",
					Order.class
			).list();

			/*
				select o1_0.id, o1_0.name
    			from "Order" as o1_0
    			inner join Item as l1_0 on l1_0."order_id" = o1_0.id
			 */

			assertJoinFrequencyInSQL( true, 1, 0 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 );
			List<Item> lineItems = order.getLineItems();
			assertFalse( Hibernate.isInitialized( lineItems ) );
			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

			assertThat( lineItems.size(), is( 2 ) );

			/*
				select l1_0."order_id", l1_0.id
    			from Item as l1_0
    			where l1_0."order_id" = ?
			 */

			assertNoJoinInSQL( 0 );
			sqlStatementInterceptor.clear();

			assertTrue( Hibernate.isInitialized( lineItems ) );
			for ( Item itm : lineItems ) {
				assertThat( itm.getOrder(), is( order ) );
			}
			assertThat( statistics.getPrepareStatementCount(), is( 2L ) );
		} );

		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Order> results = session.createQuery(
					"select o from Order o join fetch o.lineItems l join fetch l.order",
					Order.class
			).list();

			/*
				select o1_0.id, l1_0."order_id", l1_0.id, o1_0.name
    			from "Order" as o1_0
    			inner join Item as l1_0 on l1_0."order_id" = o1_0.id
			 */

			assertJoinFrequencyInSQL( true, 1, 0 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 );
			List<Item> lineItems = order.getLineItems();
			assertTrue( Hibernate.isInitialized( lineItems ) );
			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );

			assertThat( lineItems.size(), is( 2 ) );
			for ( Item itm : lineItems ) {
				assertThat( itm.getOrder(), is( order ) );
			}

			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );
		} );
	}

	@Test
	@FailureExpected(jiraKey = "no jira", reason = "order.getLineItems().size() is 4 and not 2 as it should be")
	public void testItemFetchJoin(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			Statistics statistics = session.getSessionFactory().getStatistics();
			statistics.clear();
			sqlStatementInterceptor.clear();

			List<Item> results = session.createQuery(
					"select i from Item i join fetch i.order o join fetch o.lineItems",
					Item.class
			).list();

			/*
				select i1_0.id, o1_0.id, l1_0."order_id", l1_0.id, o1_0.name
    			from Item as i1_0
    			inner join "Order" as o1_0 on i1_0."order_id" = o1_0.id
    			inner join Item as l1_0 on l1_0."order_id" = o1_0.id
			 */

			assertJoinFrequencyInSQL( true, 2, 0 );
			sqlStatementInterceptor.clear();

			Order order = results.get( 0 ).getOrder();
			assertTrue( Hibernate.isInitialized( order ) );
			assertThat( statistics.getPrepareStatementCount(), is( 1L ) );
			assertTrue( Hibernate.isInitialized( order.getLineItems() ) );


			assertThat( order.getLineItems().size(), is( 2 ) );
		} );
	}

	@Test
	@FailureExpected(reason = "It should throw an exception because query specified join fetching, but the owner of the fetched association was not present in the select list")
	public void testItemJoinWithFetchJoin(SessionFactoryScope scope) {
		Assertions.assertThrows( IllegalArgumentException.class, () ->
				scope.inTransaction( session -> {
					Statistics statistics = session.getSessionFactory().getStatistics();
					statistics.clear();
					sqlStatementInterceptor.clear();

					List<Item> results = session.createQuery(
							"select i from Item i join i.order o join fetch o.lineItems",
							Item.class
					).list();

				} )
		);
	}

	@Test
	public void testCircularReferenceDetection(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			List<Order> orders = session.createQuery(
					"select o from Order o join fetch o.lineItems",
					Order.class
			).list();
			orders.forEach(
					order ->
							order.getLineItems().forEach(
									item -> assertThat( item.getOrder(), sameInstance( order ) )
							)
			);
		} );
	}

	@Entity(name = "Order")
	@Table(name = "`Order`")
	public static class Order {
		@Id
		private Long id;

		public Order() {
		}

		public Order(Long id) {
			this.id = id;
		}

		private String name;

		@OneToMany(mappedBy = "order")
		List<Item> lineItems = new ArrayList<>();

		public List<Item> getLineItems() {
			return lineItems;
		}

		public void setLineItems(List<Item> lineItems) {
			this.lineItems = lineItems;
		}

		public void addItem(Item item) {
			this.lineItems.add( item );
			item.setOrder( this );
		}
	}

	@Entity(name = "Item")
	@Table(name = "Item")
	public static class Item {
		@Id
		private Long id;

		public Item() {
		}

		public Item(Long id) {
			this.id = id;
		}

		@ManyToOne
		private Order order;

		public Order getOrder() {
			return order;
		}

		public void setOrder(Order order) {
			this.order = order;
		}
	}

	private void assertJoinFrequencyInSQL(boolean innerJoin, int expectedCount, int sqlIndex) {
		String joinPhrase = innerJoin ? "inner join" : "left outer join";
		String sql = sqlStatementInterceptor.getSqlQueries().get( sqlIndex );
		String re = String.format( "^.+(\\s+%s\\s+.+){%d}.*$", joinPhrase, expectedCount );
		assertThat(
				String.format( "%s should show up %d time(s) in SQL: %s", joinPhrase, expectedCount, sql ),
				sql.matches( re ),
				is( true )
		);
	}

	private void assertNoJoinInSQL(int sqlIndex) {
		String sql = sqlStatementInterceptor.getSqlQueries().get( sqlIndex );
		String re = "^.+\\s+join\\s+.+$";
		assertThat( String.format( " 'join' should not appear in SQL: %s", sql ), sql.matches( re ), is( false ) );
	}

}
