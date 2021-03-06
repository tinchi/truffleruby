# frozen_string_literal: false
require 'test/unit'
require "c/bignum"

class TestBignum < Test::Unit::TestCase
  class TestDiv < Test::Unit::TestCase

    SIZEOF_BDIGIT = Bignum::SIZEOF_BDIGIT
    BITSPERDIG = Bignum::BITSPERDIG
    BDIGMAX = (1 << BITSPERDIG) - 1

    def test_divrem_normal
      x = (1 << (BITSPERDIG*2)) | (2 << BITSPERDIG) | 3
      y = (1 << BITSPERDIG) | 1
      q = (1 << BITSPERDIG) | 1
      r = 2
      assert_equal([q, r], x.big_divrem_normal(y))
    end

    def test_divrem_gmp
      x = (1 << (BITSPERDIG*2)) | (2 << BITSPERDIG) | 3
      y = (1 << BITSPERDIG) | 1
      q = (1 << BITSPERDIG) | 1
      r = 2
      assert_equal([q, r], x.big_divrem_gmp(y))
    rescue NotImplementedError
    end
  end
end
